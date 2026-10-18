package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkRateLimitedException;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.domain.GemSkillCatalog;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * 보석 매물 직업·스킬 필터(#542). 직업·스킬 목록은 정적 데이터(공식 호출 0). 조건 붙은 매물은 공식 검색 1회를 1분 캐시하고, 기능 전용 분당 몫·같은 조건 동시
 * 요청 하나로 묶기(single-flight)·사용자 몫을 남기는 게이트를 지난다. 걸러진 최저가는 시세판 칸·일별 기록에 저장하지 않는다. 틀린 조건은 입력값을 되돌리지 않는
 * 고정 문장의 400이다.
 */
@Service
public class GemSkillFilterService {
  private static final long WAIT_SECONDS = 15;
  private final GemSkillCatalog catalog;
  private final GemFilterPolicy policy;
  private final MarketAuctionService auctionService;
  private final AuctionPort auctions;
  private final AuctionSearchPort search;
  private final LostArkRequestGate gate;
  private final Clock clock;
  private final MarketMetrics metrics;
  private final ConcurrentHashMap<String, CompletableFuture<AuctionPage>> inFlight =
      new ConcurrentHashMap<>();

  public GemSkillFilterService(
      GemSkillCatalog catalog,
      GemFilterPolicy policy,
      MarketAuctionService auctionService,
      AuctionPort auctions,
      AuctionSearchPort search,
      LostArkRequestGate gate,
      Clock clock,
      MarketMetrics metrics) {
    this.catalog = catalog;
    this.policy = policy;
    this.auctionService = auctionService;
    this.auctions = auctions;
    this.search = search;
    this.gate = gate;
    this.clock = clock;
    this.metrics = metrics;
  }

  public record Listings(AuctionPage page, GemSkillCatalog.Filter filter) {}

  public GemSkillCatalog catalog() {
    requireEnabled();
    return catalog;
  }

  /** 조건이 없으면 지금 매물 경로 그대로, 있으면 조건 붙은 검색. */
  public Listings listings(String kind, int level, int page, String className, Integer skill) {
    var filter = filter(kind, className, skill);
    if (filter.isEmpty()) return new Listings(auctionService.gemListings(kind, level, page), null);
    var query = MarketAuctionService.gemQuery(kind, level, page, filter.get());
    return new Listings(filtered(query), filter.get());
  }

  Optional<GemSkillCatalog.Filter> filter(String kind, String className, Integer skill) {
    var name = className == null || className.isBlank() ? null : className.strip();
    if (name == null && skill == null) return Optional.empty();
    requireEnabled();
    try {
      return catalog.filter(kind, name, skill);
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(MarketErrorCode.INVALID_GEM_FILTER);
    }
  }

  private AuctionPage filtered(AuctionPort.AuctionQuery query) {
    String key = query.key() + ":" + query.pageNo();
    var cached = auctions.findListings(key);
    if (cached.isPresent()) {
      metrics.gemFilter("cache_hit");
      return cached.get();
    }
    var mine = new CompletableFuture<AuctionPage>();
    var running = inFlight.putIfAbsent(key, mine);
    if (running != null) {
      metrics.gemFilter("joined");
      return await(running);
    }
    try {
      // 앞선 요청이 방금 끝냈을 수 있다.
      var again = auctions.findListings(key);
      var page = again.isPresent() ? again.get() : fetch(query, key);
      mine.complete(page);
      return page;
    } catch (RuntimeException exception) {
      mine.completeExceptionally(exception);
      throw exception;
    } finally {
      inFlight.remove(key, mine);
    }
  }

  private AuctionPage fetch(AuctionPort.AuctionQuery query, String key) {
    var quota = auctions.acquireGemFilterQuota(policy.quotaPerMinute()).orElse(null);
    if (quota == null) {
      metrics.gemFilter("quota_reject");
      throw busy();
    }
    AuctionPage page;
    try {
      page = gate.withUserReserve(policy.reserve(), () -> search.search(query));
    } catch (BusinessException exception) {
      // 공식 호출 전에 거절됐으면 몫을 돌려준다(#553): 자기 한도를 다 쓴 클라이언트가 공식 호출 없이 모두의 몫을 바닥내지 않게.
      if (beforeOfficialCall(exception)) auctions.releaseGemFilterQuota(quota);
      if (exception.errorCode() == LostArkErrorCode.LOST_ARK_RATE_LIMITED
          || exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE) {
        metrics.gemFilter("gate_reject");
        throw busy();
      }
      throw exception;
    }
    metrics.gemFilter("official_call");
    auctions.saveListings(key, page);
    return page;
  }

  private AuctionPage await(CompletableFuture<AuctionPage> running) {
    try {
      return running.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(exception.getCause());
    } catch (TimeoutException exception) {
      metrics.gemFilter("wait_timeout");
      throw new BusinessException(MarketErrorCode.MARKET_SEARCH_BUSY, Duration.ofSeconds(5));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new BusinessException(MarketErrorCode.MARKET_SEARCH_BUSY, Duration.ofSeconds(5));
    }
  }

  /**
   * 공식 서버에 요청을 보내기 전에 막힌 거절인가: 관문의 예산·대기·동시 상한, 관문 저장소 장애, 클라이언트 공식 호출 한도. 공식 서버가 429를 준 것은 이미 호출한
   * 것이라 아니다.
   */
  static boolean beforeOfficialCall(BusinessException exception) {
    if (exception instanceof LostArkRateLimitedException limited)
      return limited.limitCause() != LostArkRateLimitedException.Cause.UPSTREAM_429;
    return exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE
        || exception.errorCode() == LostArkErrorCode.CLIENT_RATE_LIMITED;
  }

  private void requireEnabled() {
    if (!policy.enabled()) throw new BusinessException(MarketErrorCode.GEM_FILTER_DISABLED);
  }

  private BusinessException busy() {
    long seconds = 60 - clock.instant().getEpochSecond() % 60;
    return new BusinessException(
        MarketErrorCode.MARKET_SEARCH_BUSY, Duration.ofSeconds(Math.max(1, seconds)));
  }
}
