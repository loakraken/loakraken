package com.loakraken.market.application;

import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.PolishOption;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** T4 악세 경매 검색기(N13) 조건과 결과. */
public final class AccessorySearch {
  private AccessorySearch() {}

  /**
   * @param polish 최대 3줄. level이 null이면 그 옵션이 붙어 있기만 하면 된다(T4 하~상)
   * @param enlightenmentMin 깨달음 최솟값(서버 필터)
   * @param mainStatRangeMinPct 힘민지 범위 % 최솟값(받은 페이지 안에서 거르는 뒤 필터)
   * @param minQuality 최소 품질(0~100, 서버 필터)
   * @param page 공식 페이지 시작 번호(1~10). 뒤 필터면 여기서부터 최대 maxScanPages장을 읽는다
   */
  public record Command(
      Slot slot,
      Grade grade,
      List<PolishFilter> polish,
      Integer enlightenmentMin,
      Integer mainStatRangeMinPct,
      Integer minQuality,
      int page) {
    public Command {
      polish = polish == null ? List.of() : List.copyOf(polish);
    }

    boolean postFilter() {
      return mainStatRangeMinPct != null && mainStatRangeMinPct > 0;
    }
  }

  public record PolishFilter(PolishOption option, Level level) {}

  /**
   * @param complete 뒤 필터가 없거나, 결과가 10개 이상 찼거나, 공식 매물을 끝까지 읽었으면 true
   * @param scannedPages 이번에 읽은 공식 페이지 수
   * @param nextPage 이어서 읽을 공식 페이지. 더 없으면 null
   */
  public record Result(
      long totalCount,
      int page,
      Instant observedAt,
      boolean cached,
      boolean complete,
      int scannedPages,
      Integer nextPage,
      List<Item> items) {
    public Result {
      items = items == null ? List.of() : List.copyOf(items);
    }

    Result asCached() {
      return new Result(
          totalCount, page, observedAt, true, complete, scannedPages, nextPage, items);
    }
  }

  /**
   * @param polishStage 연마 효과 줄 수(범위 % 계산 기준)
   * @param mainStat 힘·민첩·지능(셋은 같은 값) 중 가장 큰 값
   */
  public record Item(
      String name,
      String grade,
      Integer quality,
      List<Polish> polish,
      int polishStage,
      Integer enlightenment,
      Integer mainStat,
      BigDecimal mainStatRangePct,
      Integer tradeAllowCount,
      Long buyPrice,
      Long bidStartPrice,
      Instant endDate,
      String icon,
      List<BaseEffect> baseEffects) {
    public Item {
      polish = polish == null ? List.of() : List.copyOf(polish);
      // 기본 효과가 없으면 빈 목록(null 아님, 계약).
      baseEffects = baseEffects == null ? List.of() : List.copyOf(baseEffects);
    }
  }

  /** 기본 효과 한 줄: 공식 옵션 중 STAT 줄(힘·민첩·지능·체력 등)의 이름과 숫자 값. */
  public record BaseEffect(String name, BigDecimal value) {}

  /**
   * @param name 공식 연마 효과 이름(앞뒤 공백 제거)
   * @param option 검색 조건에 쓰는 T4 핵심 연마 효과면 그 값, 아니면 null(최대 생명력 등)
   * @param level T4 하·중·상 값이면 그 단계, 아니면 null
   */
  public record Polish(
      String name, PolishOption option, BigDecimal value, boolean percentage, Level level) {}
}
