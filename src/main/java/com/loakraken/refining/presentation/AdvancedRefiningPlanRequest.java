package com.loakraken.refining.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.loakraken.refining.domain.RefiningRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 상급 재련 기대 비용 요청(계약 초안 2026-11-22). */
public record AdvancedRefiningPlanRequest(
    @NotNull
        @Schema(
            description =
                "UPHWA: 운명의 업화. JEONYUL(운명의 전율)은 상급 재련 표를 확인하기 전이라 400 REFINING_ADVANCED_UNCONFIRMED",
            example = "UPHWA")
        RefiningRules.Grade grade,
    @NotNull
        @Schema(
            description = "WEAPON: 무기, ARMOR: 방어구. ARM_GUARD(완갑)는 상급 재련이 없어 400",
            example = "ARMOR")
        RefiningRules.EquipmentType equipmentType,
    @NotNull @Min(0) @Max(39) @Schema(description = "현재 상급 재련 단계(0~39)", example = "0")
        Integer fromStage,
    @NotNull @Min(1) @Max(40) @Schema(description = "목표 상급 재련 단계(1~40, 현재보다 큼)", example = "10")
        Integer toStage) {

  @AssertTrue(message = "목표 단계는 현재 단계보다 커야 합니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isValidRange() {
    return fromStage == null || toStage == null || toStage > fromStage;
  }

  @AssertTrue(message = "상급 재련은 무기·방어구만 있습니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isWeaponOrArmor() {
    return equipmentType != RefiningRules.EquipmentType.ARM_GUARD;
  }

  /** 규칙 파일의 장비 종류 키(WEAPON·ARMOR). */
  @JsonIgnore
  String ruleType() {
    return equipmentType == RefiningRules.EquipmentType.WEAPON ? "WEAPON" : "ARMOR";
  }
}
