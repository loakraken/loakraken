package com.loakraken.stats.presentation;

import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 통계·랭킹 공통 필터. 적용 버튼 없이 바로 반영하는 화면을 위해 모두 선택값이다. */
public record StatsFilterRequest(
    @Size(max = 32) @Pattern(regexp = "[\\p{L}\\p{N}]+") @Schema(description = "서버명. 생략하면 전체")
        String server,
    @Schema(description = "DEALER 또는 SUPPORT. 생략하면 전체") StatsRules.Role role,
    @Size(max = 32) @Pattern(regexp = "[\\p{L}\\p{N}]+") @Schema(description = "직업명. 생략하면 전체")
        String className,
    @Schema(description = "최근 확인 기간(일): 7, 30, 90", defaultValue = "30") Integer activeDays,
    @DecimalMin("0") @DecimalMax("2000") @Schema(description = "최소 아이템 레벨", defaultValue = "1640")
        BigDecimal itemLevelMin,
    @DecimalMin("0") @DecimalMax("2000") BigDecimal itemLevelMax,
    @DecimalMin("0") @DecimalMax("100000") BigDecimal combatPowerMin,
    @DecimalMin("0") @DecimalMax("100000") BigDecimal combatPowerMax,
    @Schema(description = "원정대(같은 서버) 대표 캐릭터(아이템 레벨 최고)만", defaultValue = "false")
        Boolean representativeOnly) {

  @AssertTrue(message = "activeDays는 7, 30, 90 중 하나입니다.")
  @Schema(hidden = true)
  public boolean isActiveDaysAllowed() {
    return activeDays == null || StatsRules.ACTIVE_DAYS.contains(activeDays);
  }

  public StatsConditions toConditions() {
    return new StatsConditions(
        server,
        role,
        className,
        activeDays == null ? StatsRules.DEFAULT_ACTIVE_DAYS : activeDays,
        itemLevelMin,
        itemLevelMax,
        combatPowerMin,
        combatPowerMax,
        Boolean.TRUE.equals(representativeOnly));
  }
}
