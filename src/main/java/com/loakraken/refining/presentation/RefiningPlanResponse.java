package com.loakraken.refining.presentation;

import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(
    description =
        "재련 기대 비용. 기대값은 확률 분포의 평균이며, 최악은 장인의 기운으로 확정될 때까지 모두 실패한 경우입니다. 시세 환산·실링은 포함하지 않습니다.")
public record RefiningPlanResponse(
    String ruleVersion,
    SourceResponse source,
    RefiningRules.Grade grade,
    RefiningRules.EquipmentType equipmentType,
    int fromStage,
    int toStage,
    boolean useBreath,
    boolean useBook,
    boolean growthSupport,
    @Schema(description = "장비 성장(경험치) 비용을 더했는지") boolean growthCost,
    @Schema(description = "영지 연구(업화 목표 11~14단계 성장 경험치 20% 감소)를 적용했는지") boolean expResearch,
    @Schema(description = "성장 비용 출처") GrowthSourceResponse growthSource,
    List<StageResponse> stages,
    @Schema(description = "단계별 기대 소모량(소수 둘째 자리 반올림)의 합") List<QuantityResponse> expectedMaterials,
    @Schema(description = "모든 단계가 최대 시도 수까지 간 경우의 소모량") List<QuantityResponse> worstCaseMaterials,
    BigDecimal expectedGold,
    long worstCaseGold,
    @Schema(description = "계산 가정과 제외 항목") List<String> assumptions) {
  static RefiningPlanResponse from(
      RefiningRules rules,
      GrowthCosts growthCosts,
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlanRequest request,
      RefiningPlan plan) {
    var options = request.toOptions();
    return new RefiningPlanResponse(
        rules.ruleVersion(),
        new SourceResponse(
            rules.source().name(),
            rules.source().commit(),
            rules.source().license(),
            rules.source().crossCheckedWith(),
            rules.source().verifiedOn()),
        grade,
        equipmentType,
        options.fromStage(),
        options.toStage(),
        options.useBreath(),
        options.useBook(),
        options.growthSupport(),
        options.growthCost(),
        options.expResearch(),
        new GrowthSourceResponse(
            growthCosts.ruleVersion(),
            growthCosts.source().name(),
            growthCosts.source().crossCheckedWith(),
            growthCosts.source().verifiedOn(),
            growthCosts.research().text()),
        plan.stages().stream().map(StageResponse::from).toList(),
        quantities(plan.expectedMaterials()),
        quantities(plan.worstCaseMaterials()),
        plan.expectedGold(),
        plan.worstCaseGold(),
        List.of(
            "실패할 때마다 기본 확률의 10%가 오르며 최대 10회까지 누적합니다.",
            "장인의 기운은 시도 최종 성공률(숨결·책 포함) × 0.465만큼 쌓이고 100%면 다음 시도가 확정 성공입니다.",
            "현재 장인의 기운·실패 횟수는 첫 단계에만 적용하고 이후 단계는 0에서 시작합니다.",
            options.growthCost()
                ? "장비 성장(경험치) 비용은 단계마다 한 번 드는 운명의 파편을 재료에 더합니다(목표 사이트 2곳의 값 일치, 2026-10-10). 실링은 수량만 보여 주고 합계에 넣지 않습니다."
                : "장비 성장(경험치) 비용은 넣지 않았습니다.",
            options.growthCost() && options.expResearch()
                ? "영지 연구 '"
                    + growthCosts.research().text()
                    + "'를 적용했습니다(목표 사이트 중 한 곳의 기본과 같음). 다른 한 곳은 이 연구를 적용하지 않아, 끄면 그쪽과 같은 값입니다."
                : "영지 연구(성장 경험치 감소)는 적용하지 않았습니다.",
            "전율 목표 21단계는 일반 성장 대신 한계 해방(고통의 가시·해방 비용)으로 올라가 성장 비용이 0이고, 한계 해방 비용은 공식 수치가 없어 넣지 않습니다(공지 13339).",
            "강화 재봉술·야금술, 운명의 전율 책, 이벤트·익스프레스 추가 확률, 특수 재련은 계산하지 않습니다.",
            "골드 환산 시세는 포함하지 않습니다. 재료 견적 API로 기대·최악 수량을 환산할 수 있습니다."));
  }

  private static List<QuantityResponse> quantities(List<RefiningPlan.Quantity> values) {
    return values.stream()
        .map(value -> new QuantityResponse(value.name(), value.amount()))
        .toList();
  }

  public record SourceResponse(
      @Schema(description = "단계별 수치 출처") String name,
      String commit,
      String license,
      @Schema(description = "교차 검증한 다른 출처") String crossCheckedWith,
      String verifiedOn) {}

  public record StageResponse(
      @Schema(description = "이 단계 재련 후 단계") int targetStage,
      @Schema(description = "첫 시도 성공률(만분율, 1 = 0.01%). 숨결·책 포함") int firstAttemptSuccessRateBp,
      @Schema(description = "기대 시도 수") BigDecimal expectedAttempts,
      @Schema(description = "50% 확률로 이 시도 안에 성공") int medianAttempts,
      @Schema(description = "90% 확률로 이 시도 안에 성공") int p90Attempts,
      @Schema(description = "장인의 기운으로 확정되는 최대 시도 수") int maxAttempts,
      List<QuantityResponse> expectedMaterials,
      List<QuantityResponse> worstCaseMaterials,
      BigDecimal expectedGold,
      long worstCaseGold,
      @Schema(description = "이 단계 성장(경험치) 비용. growthCost=false면 null")
          @com.loakraken.common.api.NullableResponse
          GrowthResponse growth) {
    static StageResponse from(RefiningPlan.StagePlan stage) {
      return new StageResponse(
          stage.targetStage(),
          stage.firstAttemptSuccessRateBp(),
          stage.expectedAttempts(),
          stage.medianAttempts(),
          stage.p90Attempts(),
          stage.maxAttempts(),
          quantities(stage.expectedMaterials()),
          quantities(stage.worstCaseMaterials()),
          stage.expectedGold(),
          stage.worstCaseGold(),
          stage.growth() == null
              ? null
              : new GrowthResponse(
                  stage.growth().shard(),
                  stage.growth().silver(),
                  stage.growth().confirmed(),
                  stage.growth().research()));
    }
  }

  public record GrowthResponse(
      @Schema(description = "성장 운명의 파편(영지 연구 적용 뒤). 재료 합계의 운명의 파편에 들어 있다") BigDecimal shard,
      @Schema(description = "성장 실링(목표 사이트 1곳의 값, 수량 표시용, 합계에 넣지 않음)") long silver,
      @Schema(description = "false면 값이 확정되지 않아 계산에서 뺀 단계(shard 0). 2026-10-10 기준 해당 단계 없음")
          boolean confirmed,
      @Schema(description = "영지 연구(× 0.8)를 적용했는지") boolean research) {}

  public record GrowthSourceResponse(
      String ruleVersion,
      @Schema(description = "성장 비용 출처") String name,
      @Schema(description = "교차 확인") String crossCheckedWith,
      String verifiedOn,
      @Schema(description = "영지 연구 공식 문구(재련 가이드)") String researchText) {}

  public record QuantityResponse(String name, BigDecimal amount) {}
}
