package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.market.domain.AuctionCatalog;
import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.Role;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import com.loakraken.market.domain.MarketHistoryRange;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 시세 기간 차트(7d·14d·30d·90d·365d, 프론트 합의 11-13). 새 공식 호출 없음.
 *
 * <p>거래소 품목: 공식 상세(오늘 포함 14일, 지금과 같은 캐시·호출)에 30d 이상이면 일별 시세 표(market_price_daily)의 완료일을 더한다. 저장 표를
 * 읽지 못하면 공식 14일만 준다(stored = null, 로그). 경매장 칸: 일별 마지막 관측 최저가 표(auction_price_daily)만 읽는다. 칸은 시세 표의
 * 값 그대로 지정하고(보석 kind·level, 장신구 grade·role·minQuality·slot·levels), 표에 없는 조합은 400.
 */
@Service
public class MarketHistoryRangeService {
  private static final Logger log = LoggerFactory.getLogger(MarketHistoryRangeService.class);
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final MarketQueryService markets;
  private final MarketDailyStore daily;
  private final AuctionPort auctions;
  private final Clock clock;

  public MarketHistoryRangeService(
      MarketQueryService markets, MarketDailyStore daily, AuctionPort auctions, Clock clock) {
    this.markets = markets;
    this.daily = daily;
    this.auctions = auctions;
    this.clock = clock;
  }

  /** 거래소 품목. stored가 null이면 저장 표를 읽지 않은 것(14d 이하) 또는 읽지 못한 것. */
  public record ItemRange(
      MarketItemHistoryResult base,
      MarketHistoryRange range,
      MarketDailyStore.StoredRange stored) {}

  /** 경매장 칸. today = 응답 시각의 KST 날짜(그날 행은 진행 중 마지막 관측). */
  public record CellRange(
      MarketHistoryRange range, LocalDate today, AuctionPort.AuctionDailyRange stored) {}

  public ItemRange itemHistory(long itemId, MarketHistoryRange range) {
    var base = markets.itemHistory(itemId);
    if (range.officialOnly()) return new ItemRange(base, range, null);
    var baseDate = LocalDate.ofInstant(base.observedAt(), KST);
    try {
      return new ItemRange(base, range, daily.findRange(itemId, range.from(baseDate)));
    } catch (DataAccessException exception) {
      // 공식 14일은 그대로 답한다(저장 표 장애가 시세 상세를 막지 않게).
      log.warn(
          "event=market_range_read_failed itemId={} range={} exceptionType={}",
          itemId,
          range.value(),
          exception.getClass().getSimpleName());
      return new ItemRange(base, range, null);
    }
  }

  public CellRange gemHistory(String kind, int level, MarketHistoryRange range) {
    return cell(gemCellKey(kind, level), range);
  }

  public CellRange accessoryHistory(
      Grade grade,
      Role role,
      Integer minQuality,
      Slot slot,
      List<Level> levels,
      MarketHistoryRange range) {
    var options = AuctionCatalog.options(role, slot);
    // 표에 보이는 칸만(프론트 합의: 표의 값과 levels 순서 그대로, 그 밖은 400).
    if (levels.size() != options.size()
        || !AuctionCatalog.combinations(options.size()).contains(levels)
        || !AuctionCatalog.shown(levels))
      throw new BusinessException(MarketErrorCode.INVALID_AUCTION_LEVELS);
    return cell(accessoryCellKey(grade, role, minQuality, slot, levels), range);
  }

  private CellRange cell(String key, MarketHistoryRange range) {
    var today = today();
    try {
      return new CellRange(range, today, auctions.dailyRange(key, range.from(today)));
    } catch (DataAccessException exception) {
      log.warn(
          "event=auction_range_read_failed range={} exceptionType={}",
          range.value(),
          exception.getClass().getSimpleName());
      throw new BusinessException(MarketErrorCode.MARKET_CACHE_UNAVAILABLE);
    }
  }

  /** 보석 표 칸 키(표·일별 기록과 같은 키). */
  public static String gemCellKey(String kind, int level) {
    return MarketAuctionService.gemQuery(kind, level, 1).key();
  }

  /** 장신구 표 칸 키(표·일별 기록과 같은 키). minQuality는 표 조회처럼 정리된 값(null = 무관). */
  public static String accessoryCellKey(
      Grade grade, Role role, Integer minQuality, Slot slot, List<Level> levels) {
    return MarketAuctionService.accessoryQuery(
            grade, slot, AuctionCatalog.options(role, slot), levels, minQuality, 1)
        .key();
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(KST));
  }
}
