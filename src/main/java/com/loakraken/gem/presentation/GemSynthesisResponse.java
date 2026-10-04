package com.loakraken.gem.presentation;

import com.loakraken.gem.domain.GemSynthesisPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GemSynthesisResponse(
    String ruleVersion,
    LocalDate rulesVerifiedOn,
    List<String> sources,
    int tier,
    int baseLevel,
    int targetLevel,
    long targetQuantity,
    @Schema(description = "현재 수량만으로 가능하면 READY, 기준 레벨 보석을 더 확보해야 하면 MISSING_BASE_GEMS")
        Status status,
    long ownedTargetUsed,
    @Schema(description = "보유 목표 보석 차감 후 새로 합성할 목표 레벨 개수") long newTargetQuantity,
    @Schema(description = "계획을 실행하기 위해 추가 확보해야 할 기준 레벨 보석 개수") long additionalBaseQuantity,
    @Schema(description = "추가 보석 확보를 포함한 조건부 계획의 전체 합성 횟수") long synthesisCount,
    List<GemInventoryUsageResponse> inventory,
    @Schema(description = "추가 기준 보석 확보 후 실행할 순서. outputQuantity는 해당 단계 합성 횟수와 같습니다.")
        List<GemSynthesisStepResponse> steps,
    @Schema(description = "새로운 합성 한 번의 종류별 확률. 특정 스킬·목표 전체 결과의 보장 확률이 아닙니다.")
        List<GemSynthesisOutcomeResponse> outcomesPerSynthesis) {
  public enum Status {
    READY,
    MISSING_BASE_GEMS
  }

  public static GemSynthesisResponse from(GemSynthesisPlan plan) {
    return new GemSynthesisResponse(
        "t4-gem-synthesis-v1",
        LocalDate.of(2026, 10, 4),
        List.of(
            "https://lostark.game.onstove.com/GameGuide/Pages/보석",
            "https://lostark.game.onstove.com/Probability/보석%20합성"),
        4,
        plan.baseLevel(),
        plan.targetLevel(),
        plan.targetQuantity(),
        plan.additionalBaseQuantity() == 0 ? Status.READY : Status.MISSING_BASE_GEMS,
        plan.ownedTargetUsed(),
        plan.newTargetQuantity(),
        plan.additionalBaseQuantity(),
        plan.synthesisCount(),
        plan.inventory().stream()
            .map(
                item ->
                    new GemInventoryUsageResponse(
                        item.level(),
                        item.providedQuantity(),
                        item.usedQuantity(),
                        item.remainingQuantity()))
            .toList(),
        plan.steps().stream()
            .map(
                step ->
                    new GemSynthesisStepResponse(
                        step.inputLevel(),
                        step.outputLevel(),
                        step.inputQuantity(),
                        step.outputQuantity()))
            .toList(),
        List.of(
            new GemSynthesisOutcomeResponse("겁화", new BigDecimal("0.5")),
            new GemSynthesisOutcomeResponse("작열", new BigDecimal("0.5"))));
  }

  public record GemInventoryUsageResponse(
      int level, long providedQuantity, long usedQuantity, long remainingQuantity) {}

  public record GemSynthesisStepResponse(
      int inputLevel, int outputLevel, long inputQuantity, long outputQuantity) {}

  public record GemSynthesisOutcomeResponse(String type, BigDecimal probability) {}
}
