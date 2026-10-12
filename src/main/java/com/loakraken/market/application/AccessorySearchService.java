package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.ClientLimiter;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.AccessorySearch.Command;
import com.loakraken.market.application.AccessorySearch.Item;
import com.loakraken.market.application.AccessorySearch.Polish;
import com.loakraken.market.application.AccessorySearch.Result;
import com.loakraken.market.application.AuctionPort.AuctionListing;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionPort.AuctionQuery;
import com.loakraken.market.application.AuctionPort.EtcFilter;
import com.loakraken.market.domain.AccessoryStatRange;
import com.loakraken.market.domain.AuctionCatalog;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * T4 악세 경매 검색기(N13). 조건을 공식 경매장 서버 필터(연마 효과 7·깨달음 8/1·힘민지 1/11, 2026-10-12 `/auctions/options` 확인)로
 * 바꿔 우리 서버 키로 검색한다.
 *
 * <p>호출 아끼기(설계 '호출 아끼기'):
 *
 * <ol>
 *   <li>결과는 조건·뒤 필터·정렬·페이지 키로 {@link AccessorySearchPolicy#cacheTtl()} 캐시한다(빈 결과 포함). 같은 키 동시 요청은
 *       공식 호출 하나로 묶는다.
 *   <li>캐시 미스 검색은 요청 클라이언트를 AUCTION_SEARCH 버킷에 센다(넘으면 429).
 *   <li>공식 호출마다 기능 몫(모든 사용자 합 분당 {@link AccessorySearchPolicy#quotaPerMinute()})을 가져오고, {@code
 *       gate.withUserReserve(reserve, …)}로 부른다. 사용자 경로 몫을 남기면서 요청 클라이언트의 공식 호출 한도에도 매긴다.
 *       첫 호출부터 몫·게이트에 막히면 503 MARKET_SEARCH_BUSY + Retry-After이고, 캐시에 있는 결과는 계속 준다.
 *   <li>힘민지 범위 %는 서버 필터가 수치 최솟값만 받으므로 받은 페이지 안에서 거른다(최대 maxScanPages장, complete·scannedPages).
 * </ol>
 */
@Service
public class AccessorySearchService {
  public static final String RULE_VERSION = "accessory-search-v1";
  static final int PAGE_SIZE = 10;
  static final int MAX_PAGE = 10;
  private static final Set<String> MAIN_STATS = Set.of("힘", "민첩", "지능");
  private final AuctionSearchPort search;
  private final AccessorySearchStorePort store;
  private final LostArkRequestGate gate;
  private final AccessorySearchPolicy policy;
  private final MarketMetrics metrics;
  private final Clock clock;
  private final ConcurrentHashMap<String, CompletableFuture<Result>> flights =
      new ConcurrentHashMap<>();

  public AccessorySearchService(
      AuctionSearchPort search,
      AccessorySearchStorePort store,
      LostArkRequestGate gate,
      AccessorySearchPolicy policy,
      MarketMetrics metrics,
      Clock clock) {
    this.search = search;
    this.store = store;
    this.gate = gate;
    this.policy = policy;
    this.metrics = metrics;
    this.clock = clock;
  }

  public Result search(Command command) {
    validate(command);
    var query = query(command);
    String key = cacheKey(command, query);
    var cached = store.find(key);
    if (cached.isPresent()) {
      metrics.cache(MarketMetrics.Kind.AUCTION_SEARCH, MarketMetrics.Outcome.HIT);
      return cached.get().asCached();
    }
    metrics.cache(MarketMetrics.Kind.AUCTION_SEARCH, MarketMetrics.Outcome.MISS);
    gate.chargeClient(ClientLimiter.Bucket.AUCTION_SEARCH);
    return flight(
        key,
        () -> {
          var again = store.find(key);
          if (again.isPresent()) return again.get().asCached();
          var scan = scan(command, query);
          if (!scan.interrupted()) store.save(key, scan.result(), policy.cacheTtl());
          if (!scan.result().complete()) metrics.auctionSearch("incomplete");
          return scan.result();
        });
  }

  /** 조건 → 공식 검색 조건(1페이지). 연마 단계를 고르지 않으면 T4 하~상 범위로 건다. */
  static AuctionQuery query(Command command) {
    var filters = new ArrayList<EtcFilter>();
    for (var line : command.polish()) {
      var option = line.option();
      int min = option.value(line.level() == null ? Level.LOW : line.level());
      int max = option.value(line.level() == null ? Level.HIGH : line.level());
      filters.add(new EtcFilter(AuctionCatalog.POLISH_OPTION, option.code(), min, max));
    }
    if (command.enlightenmentMin() != null && command.enlightenmentMin() > 0)
      filters.add(
          new EtcFilter(
              AuctionCatalog.ARK_PASSIVE_OPTION,
              AuctionCatalog.ENLIGHTENMENT_SECOND,
              command.enlightenmentMin(),
              AccessoryStatRange.enlightenmentMax(command.grade(), command.slot())));
    if (command.postFilter())
      // 범위 %의 필요조건(0단계 기준 하한)으로 미리 좁힌다. 실제 판정은 뒤 필터가 한다.
      filters.add(
          new EtcFilter(
              AuctionCatalog.BASE_EFFECT_OPTION,
              AuctionCatalog.MAIN_STAT_SECOND,
              AccessoryStatRange.mainStatLowerBound(command.slot(), command.mainStatRangeMinPct()),
              AccessoryStatRange.mainStatMax(command.slot(), AccessoryStatRange.MAX_STAGE)));
    var quality = command.minQuality();
    return new AuctionQuery(
        command.slot().categoryCode(),
        AuctionCatalog.TIER,
        command.grade().official(),
        null,
        quality == null || quality == 0 ? null : quality,
        filters,
        command.page());
  }

  static String cacheKey(Command command, AuctionQuery query) {
    return query.key()
        + ":range="
        + (command.postFilter() ? command.mainStatRangeMinPct() : 0)
        + ":BUY_PRICE:"
        + command.page();
  }

  private void validate(Command command) {
    if (command.slot() == null
        || command.grade() == null
        || command.page() < 1
        || command.page() > MAX_PAGE
        || command.polish().size() > AccessoryStatRange.MAX_STAGE) throw invalid();
    var seen = EnumSet.noneOf(AuctionCatalog.PolishOption.class);
    for (var line : command.polish()) {
      if (line == null || line.option() == null) throw invalid();
      if (!seen.add(line.option())) throw invalid();
      if (!AuctionCatalog.fits(command.slot(), line.option())) throw invalid();
    }
    var enlightenment = command.enlightenmentMin();
    if (enlightenment != null
        && (enlightenment < 0
            || enlightenment
                > AccessoryStatRange.enlightenmentMax(command.grade(), command.slot())))
      throw invalid();
    var pct = command.mainStatRangeMinPct();
    if (pct != null && (pct < 0 || pct > 100)) throw invalid();
    var quality = command.minQuality();
    if (quality != null && (quality < 0 || quality > 100)) throw invalid();
  }

  private Scan scan(Command command, AuctionQuery query) {
    int maxPages = command.postFilter() ? policy.maxScanPages() : 1;
    var items = new ArrayList<Item>();
    long total = 0;
    Instant observedAt = null;
    int scanned = 0;
    boolean exhausted = false;
    boolean interrupted = false;
    for (int i = 0; i < maxPages; i++) {
      int pageNo = command.page() + i;
      AuctionPage page;
      try {
        page = call(query.page(pageNo));
      } catch (BusinessException exception) {
        if (scanned == 0) throw exception;
        // 몫·한도·오류로 다음 페이지를 못 읽으면 읽은 만큼만 주고 캐시하지 않는다.
        interrupted = true;
        break;
      }
      scanned++;
      total = page.totalCount();
      if (observedAt == null) observedAt = page.observedAt();
      for (var listing : page.items()) {
        var item = item(command.slot(), listing);
        if (matches(command, item)) items.add(item);
      }
      exhausted = page.items().size() < PAGE_SIZE || (long) pageNo * PAGE_SIZE >= total;
      if (exhausted || items.size() >= PAGE_SIZE) break;
    }
    int last = command.page() + scanned - 1;
    Integer nextPage = exhausted || last >= MAX_PAGE ? null : last + 1;
    boolean complete = !command.postFilter() || exhausted || items.size() >= PAGE_SIZE;
    return new Scan(
        new Result(
            total,
            command.page(),
            observedAt == null ? clock.instant() : observedAt,
            false,
            complete,
            scanned,
            nextPage,
            items),
        interrupted);
  }

  /** 공식 호출 하나. 기능 몫·게이트에 막히면 MARKET_SEARCH_BUSY + Retry-After(지금 분이 끝날 때까지). */
  private AuctionPage call(AuctionQuery query) {
    if (!store.tryAcquireQuota(policy.quotaPerMinute())) {
      metrics.auctionSearch("quota_reject");
      throw busy();
    }
    try {
      return gate.withUserReserve(policy.reserve(), () -> search.search(query));
    } catch (BusinessException exception) {
      if (exception.errorCode() == LostArkErrorCode.LOST_ARK_RATE_LIMITED
          || exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE) {
        metrics.auctionSearch("gate_reject");
        throw busy();
      }
      throw exception;
    }
  }

  private BusinessException busy() {
    long seconds = 60 - clock.instant().getEpochSecond() % 60;
    return new BusinessException(
        MarketErrorCode.MARKET_SEARCH_BUSY, Duration.ofSeconds(Math.max(1, seconds)));
  }

  private static BusinessException invalid() {
    return new BusinessException(MarketErrorCode.INVALID_AUCTION_FILTER);
  }

  private static boolean matches(Command command, Item item) {
    if (!command.postFilter()) return true;
    return item.mainStatRangePct() != null
        && item.mainStatRangePct()
                .compareTo(java.math.BigDecimal.valueOf(command.mainStatRangeMinPct()))
            >= 0;
  }

  static Item item(Slot slot, AuctionListing listing) {
    var polish = new ArrayList<Polish>();
    Integer enlightenment = null;
    Integer mainStat = null;
    for (var option : listing.options()) {
      var name = option.name() == null ? "" : option.name().strip();
      if ("ACCESSORY_UPGRADE".equals(option.type())) {
        var known = AuctionCatalog.PolishOption.byOfficialName(name).orElse(null);
        var level = known == null ? null : known.levelOf(option.value()).orElse(null);
        polish.add(new Polish(name, known, option.value(), option.percentage(), level));
      } else if ("ARK_PASSIVE".equals(option.type())
          && "깨달음".equals(name)
          && option.value() != null) {
        enlightenment = option.value().intValue();
      } else if ("STAT".equals(option.type())
          && MAIN_STATS.contains(name)
          && option.value() != null) {
        int value = option.value().intValue();
        mainStat = mainStat == null ? value : Math.max(mainStat, value);
      }
    }
    int stage = polish.size();
    return new Item(
        listing.name(),
        listing.grade(),
        listing.quality(),
        polish,
        stage,
        enlightenment,
        mainStat,
        AccessoryStatRange.mainStatRangePct(slot, stage, mainStat),
        listing.tradeAllowCount(),
        listing.buyPrice(),
        listing.bidStartPrice(),
        listing.endDate());
  }

  private Result flight(String key, Supplier<Result> loader) {
    var future = new CompletableFuture<Result>();
    var existing = flights.putIfAbsent(key, future);
    if (existing != null) {
      try {
        return existing.join().asCached();
      } catch (CompletionException exception) {
        if (exception.getCause() instanceof RuntimeException cause) throw cause;
        throw exception;
      }
    }
    try {
      var result = loader.get();
      future.complete(result);
      return result;
    } catch (RuntimeException exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      flights.remove(key, future);
    }
  }

  private record Scan(Result result, boolean interrupted) {}
}
