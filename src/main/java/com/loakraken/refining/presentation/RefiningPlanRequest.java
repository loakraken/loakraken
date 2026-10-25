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
    @NotNull @Schema(description = "UPHWA: 운명의 업화, JEONYUL: 운명의 전율", example = "UPHWA")
        RefiningRules.Grade grade,
    @NotNull @Schema(example = "ARMOR") RefiningRules.EquipmentType equipmentType,
    @NotNull @Min(10) @Max(24) @Schema(description = "현재 재련 단계", example = "18") Integer fromStage,
    @NotNull @Min(11) @Max(25) @Schema(description = "목표 재련 단계", example = "20") Integer toStage,
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
        @Digits(integer = 3, fraction = 2)
        @Schema(description = "첫 단계의 현재 장인의 기운(%)", defaultValue = "0", example = "46.50")
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
        Breath breath) {
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

  RefiningPlan.Options toOptions() {
    return new RefiningPlan.Options(
        fromStage,
        toStage,
        effectiveBreath() == Breath.MAX,
        !Boolean.FALSE.equals(useBook),
        growthSupport == null || growthSupport,
        currentArtisanEnergy == null ? 0 : currentArtisanEnergy.movePointRight(2).intValueExact(),
        currentFailedAttempts == null ? 0 : currentFailedAttempts,
        Boolean.TRUE.equals(growthCost),
        expResearch == null || expResearch);
  }
}
