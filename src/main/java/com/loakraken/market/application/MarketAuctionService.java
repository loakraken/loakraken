package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionPort.AuctionQuery;
import com.loakraken.market.application.AuctionPort.EtcFilter;
import com.loakraken.market.domain.AuctionCatalog;
import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.PolishOption;
import com.loakraken.market.domain.AuctionCatalog.Role;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 경매장 보석·장신구 시세 표(auction-board-v1).
 *
 * <p>표의 칸마다 공식 검색이 1회 든다(보석 20칸, 장신구 표 19~24칸). 사용자 검색을 보호하기 위해 칸을 따로 3분 캐시하고, 비어 있는 칸만 {@code
 * gate.withReserve(RESERVE, …)}로 채운다. 공유 게이트가 사용자 몫(reserve)을 남기는지 판정과 기록을 한 번에 하므로, 다른 요청·인스턴스가
 * 끼어들어도 사용자 몫을 파고들지 않는다(#130). 한 요청에 최대 {@link #MAX_FETCH_PER_REQUEST}칸, 동시에 한 요청만 채운다. 채우지 못한 칸은
 * PENDING으로 준다. 전일 대비는 우리가 관측한 KST 날짜별 마지막 최저가로 계산한다.
 */
@Service
public class MarketAuctionService {
  public static final int MAX_FETCH_PER_REQUEST = 24;
  public static final int RESERVE = 40;
  public static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final Logger log = LoggerFactory.getLogger(MarketAuctionService.class);
  private final AuctionPort auctions;
  private final AuctionSearchPort search;
  private final LostArkRequestGate gate;
  private final Clock clock;
  private final ReentrantLock fillLock = new ReentrantLock();

  public MarketAuctionService(
      AuctionPort auctions, AuctionSearchPort search, LostArkRequestGate gate, Clock clock) {
    this.auctions = auctions;
    this.search = search;
    this.gate = gate;
    this.clock = clock;
  }

  public enum Status {
    READY,
    PENDING
  }

  public List<GemCell> gems() {
    var cells = new LinkedHashMap<String, GemSpec>();
    for (var kind : AuctionCatalog.GEM_KINDS)
      for (int level = AuctionCatalog.MAX_GEM_LEVEL; level >= 1; level--)
        cells.put(
            gemQuery(kind, level, 1).key(), new GemSpec(kind, level, gemQuery(kind, level, 1)));
    var filled = fill(cells.values().stream().map(GemSpec::query).toList());
    var result = new ArrayList<GemCell>();
    for (var spec : cells.values()) {
      var value = filled.get(spec.query().key());
      result.add(
          new GemCell(
              spec.kind(),
              spec.level(),
              AuctionCatalog.gemName(spec.kind(), spec.level()),
              value.cell(),
              value.previousDayChangeRate(),
              value.cell() == null ? Status.PENDING : Status.READY));
    }
    return result;
  }

  public List<AccessorySlot> accessories(Grade grade, Role role, Integer minQuality) {
    var specs = new ArrayList<AccessorySpec>();
    for (var slot : Slot.values()) {
      var options = AuctionCatalog.options(role, slot);
      for (var levels : AuctionCatalog.combinations(options.size()))
        specs.add(
            new AccessorySpec(
                slot,
                options,
                levels,
                accessoryQuery(grade, slot, options, levels, minQuality, 1)));
    }
    var filled = fill(specs.stream().map(AccessorySpec::query).toList());
    var slots = new ArrayList<AccessorySlot>();
    for (var slot : Slot.values()) {
      var cells = new ArrayList<AccessoryCell>();
      for (var spec : specs) {
        if (spec.slot() != slot) continue;
        var value = filled.get(spec.query().key());
        cells.add(
            new AccessoryCell(
                spec.levels(),
                AuctionCatalog.label(spec.levels()),
                AuctionCatalog.optionLabels(spec.options(), spec.levels()),
                value.cell(),
                value.previousDayChangeRate(),
                value.cell() == null ? Status.PENDING : Status.READY));
      }
      slots.add(new AccessorySlot(slot, AuctionCatalog.options(role, slot), cells));
    }
    return slots;
  }

  public AuctionPage gemListings(String kind, int level, int page) {
    return listings(gemQuery(kind, level, page));
  }

  public AuctionPage accessoryListings(
      Grade grade, Role role, Slot slot, List<Level> levels, Integer minQuality, int page) {
    var options = AuctionCatalog.options(role, slot);
    if (levels.size() != options.size())
      throw new BusinessException(MarketErrorCode.INVALID_AUCTION_LEVELS);
    return listings(accessoryQuery(grade, slot, options, levels, minQuality, page));
  }

  /** 매물 목록은 1회 검색이다. 1분 캐시하고 사용자 요청과 같은 게이트를 쓴다. */
  private AuctionPage listings(AuctionQuery query) {
    String key = query.key() + ":" + query.pageNo();
    var cached = auctions.findListings(key);
    if (cached.isPresent()) return cached.get();
    var page = search.search(query);
    auctions.saveListings(key, page);
    if (query.pageNo() == 1) remember(query.key(), toCell(page));
    return page;
  }

  private Map<String, Filled> fill(List<AuctionQuery> queries) {
    var cells = new LinkedHashMap<String, AuctionCell>();
    for (var query : queries) cells.put(query.key(), auctions.findCell(query.key()).orElse(null));
    int fetched = 0;
    if (cells.containsValue(null) && fillLock.tryLock()) {
      try {
        for (var query : queries) {
          if (cells.get(query.key()) != null) continue;
          if (fetched == MAX_FETCH_PER_REQUEST) break;
          try {
            var cell = toCell(gate.withReserve(RESERVE, () -> search.search(query)));
            fetched++;
            cells.put(query.key(), cell);
            remember(query.key(), cell);
          } catch (BusinessException exception) {
            // 호출 한도·오류는 남은 칸을 다음 요청으로 미룬다.
            log.debug("경매장 칸을 채우지 못했습니다. 코드={}", exception.errorCode().code());
            break;
          }
        }
      } finally {
        fillLock.unlock();
      }
    }
    var today = LocalDate.now(clock.withZone(KST));
    Map<String, Long> previous;
    try {
      previous = auctions.previousDay(List.copyOf(cells.keySet()), today);
    } catch (DataAccessException exception) {
      previous = Map.of();
    }
    var result = new LinkedHashMap<String, Filled>();
    for (var entry : cells.entrySet())
      result.put(
          entry.getKey(),
          new Filled(entry.getValue(), changeRate(entry.getValue(), previous.get(entry.getKey()))));
    return result;
  }

  private void remember(String key, AuctionCell cell) {
    auctions.saveCell(key, cell);
    if (cell.minBuyPrice() == null) return;
    try {
      auctions.recordDaily(key, LocalDate.ofInstant(cell.observedAt(), KST), cell);
    } catch (DataAccessException exception) {
      log.warn("경매장 일별 최저가를 남기지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  private AuctionCell toCell(AuctionPage page) {
    return new AuctionCell(page.minBuyPrice(), page.totalCount(), page.observedAt());
  }

  /** 전날 마지막 최저가 대비 변화율(%, 소수 첫째 자리). 어느 한쪽이 없으면 null. */
  static BigDecimal changeRate(AuctionCell cell, Long previous) {
    if (cell == null || cell.minBuyPrice() == null || previous == null || previous <= 0)
      return null;
    return BigDecimal.valueOf(cell.minBuyPrice() - previous)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP);
  }

  static AuctionQuery gemQuery(String kind, int level, int page) {
    if (!AuctionCatalog.GEM_KINDS.contains(kind)
        || level < 1
        || level > AuctionCatalog.MAX_GEM_LEVEL)
      throw new BusinessException(MarketErrorCode.INVALID_AUCTION_LEVELS);
    return new AuctionQuery(
        AuctionCatalog.GEM_CATEGORY,
        AuctionCatalog.TIER,
        null,
        AuctionCatalog.gemName(kind, level),
        null,
        List.of(),
        page);
  }

  static AuctionQuery accessoryQuery(
      Grade grade,
      Slot slot,
      List<PolishOption> options,
      List<Level> levels,
      Integer minQuality,
      int page) {
    var filters = new ArrayList<EtcFilter>();
    for (int i = 0; i < options.size(); i++) {
      int value = options.get(i).value(levels.get(i));
      filters.add(new EtcFilter(AuctionCatalog.POLISH_OPTION, options.get(i).code(), value, value));
    }
    return new AuctionQuery(
        slot.categoryCode(),
        AuctionCatalog.TIER,
        grade.official(),
        null,
        minQuality == null || minQuality == 0 ? null : minQuality,
        filters,
        page);
  }

  private record GemSpec(String kind, int level, AuctionQuery query) {}

  private record AccessorySpec(
      Slot slot, List<PolishOption> options, List<Level> levels, AuctionQuery query) {}

  private record Filled(AuctionCell cell, BigDecimal previousDayChangeRate) {}

  /** cell이 null이면 아직 관측하지 못한 칸(PENDING)이다. */
  public record GemCell(
      String kind,
      int level,
      String itemName,
      AuctionCell cell,
      BigDecimal previousDayChangeRate,
      Status status) {}

  public record AccessoryCell(
      List<Level> levels,
      String label,
      List<String> optionLabels,
      AuctionCell cell,
      BigDecimal previousDayChangeRate,
      Status status) {}

  public record AccessorySlot(Slot slot, List<PolishOption> options, List<AccessoryCell> cells) {}
}
