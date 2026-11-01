package com.loakraken.refining.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RefiningPlanRequest(
    @Schema(
            description =
                "UPHWA: 운명의 업화, JEONYUL: 운명의 전율. WEAPON·ARMOR는 필수, ARM_GUARD(완갑)는 단계표가 하나라 보지 않는다(생략 가능)",
            example = "UPHWA")
        RefiningRules.Grade grade,
    @NotNull
        @Schema(description = "WEAPON: 무기, ARMOR: 방어구, ARM_GUARD: 팔 보호구(완갑)", example = "ARMOR")
        RefiningRules.EquipmentType equipmentType,
    @NotNull
        @Min(0)
        @Max(24)
        @Schema(description = "현재 재련 단계. WEAPON·ARMOR 10~24, ARM_GUARD 0~24", example = "18")
        Integer fromStage,
    @NotNull
        @Min(1)
        @Max(25)
        @Schema(description = "목표 재련 단계. WEAPON·ARMOR 11~25, ARM_GUARD 1~25", example = "20")
        Integer toStage,
    @Schema(
            description = "매 시도 숨결을 최대 개수로 사용. breath를 보내면 breath가 정한다(서로 어긋나면 400)",
            defaultValue = "false")
        Boolean useBreath,
    @Schema(
            description =
                "책(재봉술·야금술)을 사용할 수 있는 단계에서 사용. 업화 11~20단계·전율 12~19단계(책 증가 = 그 단계 기본 성공률). 생략하면 true(책 포함, 2026-10-11 결정)",
            defaultValue = "true")
        Boolean useBook,
    @Schema(
            description = "업화 목표 18단계 이하 재련 골드 20% 감소(공식 성장 지원). 상시 효과라 생략하면 켬",
            defaultValue = "true")
        Boolean growthSupport,
    @DecimalMin("0")
        @DecimalMax("100")
        @Digits(integer = 3, fraction = 3)
        @Schema(
            description = "첫 단계의 현재 장인의 기운(%). 게임 화면처럼 소수 셋째 자리까지 받되 0.01%p 단위여야 함(46.500 = 46.50)",
            defaultValue = "0",
            example = "46.50")
        BigDecimal currentArtisanEnergy,
    @Min(0) @Max(10) @Schema(description = "첫 단계에서 이미 실패한 횟수", defaultValue = "0")
        Integer currentFailedAttempts,
    @Schema(
            description =
                "장비 성장(경험치) 비용(단계당 1회 운명의 파편)을 더함. 웹이 토글·표시를 넣고 true를 보낼 때까지 공개 합계가 바뀌지 않게 생략하면 끔",
            defaultValue = "false")
        Boolean growthCost,
    @Schema(
            description =
                "원정대 영지 연구 '티어 4 Lv.1590 장비 11~14단계 재련 필요 경험치 20% 감소' 적용(업화 목표 11~14단계 성장 파편 × 0.8). growthCost=true일 때만 의미가 있다. 생략하면 켬",
            defaultValue = "true")
        Boolean expResearch,
    @Schema(
            description =
                "숨결 사용 방식. NONE: 쓰지 않음, MAX: 매 시도 최대 개수, OPTIMAL: 시도마다 숨결 개수(와 useBook이면 책)를 골라 지금 시세 기준 기대 비용이 가장 작은 방법(재료 시세가 필요 — 없으면 409 REFINING_PRICE_PENDING. 서버 스위치가 꺼져 있으면 400 INVALID_REQUEST). 생략하면 useBreath(true면 MAX, 아니면 NONE)",
            example = "OPTIMAL")
        Breath breath,
    @DecimalMin("0")
        @DecimalMax("100")
        @Digits(integer = 3, fraction = 3)
        @Schema(
            description =
                "첫 단계의 현재 성공 확률(%, 게임 재련 화면 값을 숨결·책을 올리기 전에 읽은 것, 화면처럼 소수 셋째 자리까지 받음: 3.300 = 3.30). currentFailedAttempts가 없을 때 이 값으로 실패 횟수를 정한다(기본 확률 × (1 + 0.1 × 실패 0~10)과 같아야 함, 아니면 400 REFINING_SUCCESS_RATE_MISMATCH). 장인의 기운은 그 실패 횟수로 쌓일 수 있는 값이어야 함(아니면 400 REFINING_ARTISAN_ENERGY_MISMATCH). 둘 다 없으면 실패 0",
            example = "3.30")
        BigDecimal currentSuccessRate) {
  public enum Breath {
    NONE,
    MAX,
    OPTIMAL
  }

  /** breath가 있으면 그것, 없으면 useBreath(true면 MAX). */
  @JsonIgnore
  public Breath effectiveBreath() {
    if (breath != null) return breath;
    return Boolean.TRUE.equals(useBreath) ? Breath.MAX : Breath.NONE;
  }

  @AssertTrue(
      message = "useBreath와 breath가 서로 어긋납니다(useBreath=true면 NONE이 아니어야 하고 false면 MAX가 아니어야 합니다).")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isBreathConsistent() {
    if (breath == null || useBreath == null) return true;
    return useBreath ? breath != Breath.NONE : breath != Breath.MAX;
  }

  @AssertTrue(message = "목표 단계는 현재 단계보다 커야 합니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isValidRange() {
    return fromStage == null || toStage == null || toStage > fromStage;
  }

  @AssertTrue(message = "무기·방어구는 grade(UPHWA·JEONYUL)가 필요합니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isGradeGiven() {
    return equipmentType == null
        || equipmentType == RefiningRules.EquipmentType.ARM_GUARD
        || grade != null;
  }

  @AssertTrue(message = "무기·방어구는 현재 10~24단계, 목표 11~25단계입니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isStageRangeForType() {
    if (equipmentType == null || equipmentType == RefiningRules.EquipmentType.ARM_GUARD)
      return true;
    return (fromStage == null || fromStage >= 10) && (toStage == null || toStage >= 11);
  }

  @AssertTrue(message = "장인의 기운은 0.01% 단위입니다(예: 46.50, 46.500).")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isArtisanEnergyOnGrid() {
    return onHundredths(currentArtisanEnergy);
  }

  /** null이거나 0.01 단위(소수 셋째 자리가 0)인지. */
  static boolean onHundredths(BigDecimal percent) {
    return percent == null || percent.stripTrailingZeros().scale() <= 2;
  }

  /** 실패 횟수를 현재 성공 확률로 정하는지(실패 횟수는 없고 성공 확률이 있음). */
  @JsonIgnore
  boolean failuresFromSuccessRate() {
    return currentFailedAttempts == null && currentSuccessRate != null;
  }

  RefiningPlan.Options toOptions() {
    return toOptions(currentFailedAttempts == null ? 0 : currentFailedAttempts);
  }

  /**
   * startFailedAttempts는 첫 단계 실패 횟수(요청 값이나 성공 확률로 정한 값). 팔 보호구면 책·성장 지원·장비 성장 비용·영지 연구가 없어 모두
   * 끈다(응답에도 false로 나간다).
   */
  RefiningPlan.Options toOptions(int startFailedAttempts) {
    boolean armGuard = equipmentType == RefiningRules.EquipmentType.ARM_GUARD;
    return new RefiningPlan.Options(
        fromStage,
        toStage,
        effectiveBreath() == Breath.MAX,
        !armGuard && !Boolean.FALSE.equals(useBook),
        !armGuard && (growthSupport == null || growthSupport),
        currentArtisanEnergy == null ? 0 : currentArtisanEnergy.movePointRight(2).intValueExact(),
        startFailedAttempts,
        !armGuard && Boolean.TRUE.equals(growthCost),
        !armGuard && (expResearch == null || expResearch));
  }
}
