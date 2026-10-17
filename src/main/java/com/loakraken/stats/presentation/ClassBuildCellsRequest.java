package com.loakraken.stats.presentation;

import com.loakraken.stats.domain.StatsRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 직업 빌드 통계 캐릭터 한 명 칸(M26 2단계)의 조건. 기본값은 빌드 통계와 같다. */
public record ClassBuildCellsRequest(
    @NotNull
        @Size(min = 1, max = 16)
        @Pattern(regexp = "[\\p{L}\\p{N}]+")
        @Schema(description = "캐릭터 이름")
        String characterName,
    @DecimalMin("0") @DecimalMax("2000") @Schema(description = "최소 아이템 레벨(빌드 통계와 같은 기본값)")
        BigDecimal itemLevelMin,
    @DecimalMin("0") @DecimalMax("2000") @Schema(description = "최대 아이템 레벨") BigDecimal itemLevelMax,
    @Schema(description = "최근 확인 기간(일): 7, 30, 90", defaultValue = "30") Integer activeDays,
    @DecimalMin("0") @DecimalMax("2000") @Schema(description = "팔찌 전투 특성 기준 아이템 레벨(빌드 통계와 같은 기본값)")
        BigDecimal braceletItemLevelMin) {

  @AssertTrue(message = "activeDays는 7, 30, 90 중 하나입니다.")
  @Schema(hidden = true)
  public boolean isActiveDaysAllowed() {
    return activeDays == null || StatsRules.ACTIVE_DAYS.contains(activeDays);
  }

  @AssertTrue(message = "itemLevelMin이 itemLevelMax보다 큽니다.")
  @Schema(hidden = true)
  public boolean isItemLevelRangeValid() {
    return itemLevelMin == null
        || itemLevelMax == null
        || itemLevelMin.compareTo(itemLevelMax) <= 0;
  }
}
