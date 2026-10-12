package com.loakraken.market.presentation;

import com.loakraken.market.application.AccessorySearch;
import com.loakraken.market.domain.AuctionCatalog;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** T4 악세 경매 검색기(N13) 조건. */
public record AccessorySearchRequest(
    @Schema(description = "NECKLACE·EARRING·RING", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        AuctionCatalog.Slot slot,
    @Schema(description = "RELIC(유물)·ANCIENT(고대)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        AuctionCatalog.Grade grade,
    @Schema(description = "연마 효과 조건 최대 3줄. 부위에 붙는 효과만, 같은 효과는 한 번만") @Size(max = 3)
        List<@Valid @NotNull PolishLine> polish,
    @Schema(description = "깨달음 최솟값(등급·부위 최댓값 이하)") @Min(0) @Max(13) Integer enlightenmentMin,
    @Schema(description = "힘민지 범위 % 최솟값(0~100). 받은 페이지 안에서 걸러 complete·scannedPages로 알립니다")
        @Min(0)
        @Max(100)
        Integer mainStatRangeMinPct,
    @Schema(description = "최소 품질(0~100, 0이면 무관)") @Min(0) @Max(100) Integer minQuality,
    @Schema(description = "BUY_PRICE(즉시 구매가 오름차순)만 지원") Sort sort,
    @Schema(description = "공식 시작 페이지(1~10, 기본 1). 더 보기는 응답의 nextPage") @Min(1) @Max(10)
        Integer page) {

  public enum Sort {
    BUY_PRICE
  }

  /**
   * @param level LOW·MID·HIGH. 비우면 그 효과가 붙어 있기만 하면 된다
   */
  public record PolishLine(
      @Schema(description = "연마 효과(PolishOption 이름, 예: ENEMY_DAMAGE)") @NotNull
          AuctionCatalog.PolishOption option,
      @Schema(description = "LOW·MID·HIGH. 비우면 단계 무관") AuctionCatalog.Level level) {}

  AccessorySearch.Command toCommand() {
    return new AccessorySearch.Command(
        slot,
        grade,
        polish == null
            ? List.of()
            : polish.stream()
                .map(line -> new AccessorySearch.PolishFilter(line.option(), line.level()))
                .toList(),
        enlightenmentMin,
        mainStatRangeMinPct,
        minQuality,
        page == null ? 1 : page);
  }
}
