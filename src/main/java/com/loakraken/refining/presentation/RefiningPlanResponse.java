package com.loakraken.refining.presentation;

import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.refining.domain.VambraceRules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(
    description =
        "재련 기대 비용. 기대값은 확률 분포의 평균이며, 최악은 장인의 기운으로 확정될 때까지 모두 실패한 경우입니다. 시세 환산·실링은 포함하지 않습니다.")
public record RefiningPlanResponse(
    @Schema(description = "단계표 규칙 버전(ARM_GUARD는 팔 보호구 단계표 버전)") String ruleVersion,
    SourceResponse source,
    @Schema(description = "요청 등급. ARM_GUARD(완갑)는 단계표가 하나라 null")
        @com.loakraken.common.api.NullableResponse
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
    @Schema(
            description =
                "골드로 바꾸지 않고 재료 합계에도 넣지 않은 성장 비용의 합(실링, 전율 방어구 21단계 한계 해방의 고통의 가시). 단계마다 한 번 들어 기대·최악이 같다. growthCost=false거나 없으면 빈 목록")
        List<QuantityResponse> nonGold,
    @Schema(description = "계산 가정과 제외 항목") List<String> assumptions,
    @Schema(description = "이번 계산의 숨결 방식(NONE·MAX·OPTIMAL)", example = "OPTIMAL") String breath,
    @Schema(description = "OPTIMAL 계산에 쓴 재료 시세 관측 시각. 다른 방식이면 null")
        @com.loakraken.common.api.NullableResponse
        java.time.Instant pricesObservedAt,
    @Schema(
            description =
                "OPTIMAL: 계획 전체 기대 비용(골드) = 단계 기대 비용을 올리기 전 값으로 더한 뒤 한 번 올림(스펙업 여러 단계 재련 비용과 같은 방식). 화면 합계는 이 값을 쓰고 단계 expectedCostGold를 더하지 않는다(단계마다 올리면 단계 수 − 1골드까지 커진다). 다른 방식이면 null")
        @com.loakraken.common.api.NullableResponse
        Long expectedCostGold,
    @Schema(
            description =
                "첫 단계 실패 횟수를 정한 방법. REQUEST: 요청 currentFailedAttempts(없으면 0), SUCCESS_RATE: 요청 currentSuccessRate에서 정함. 모르는 값은 REQUEST처럼 다룬다",
            example = "SUCCESS_RATE")
        @com.loakraken.common.api.NullableResponse
        String failedAttemptsFrom) {
  /** 첫 단계 실패 횟수와 정한 방법(REQUEST·SUCCESS_RATE). */
  record StartFailures(int count, String from) {
    static final StartFailures NONE = new StartFailures(0, "REQUEST");
  }

  static RefiningPlanResponse from(
      RefiningRules rules,
      GrowthCosts growthCosts,
      VambraceRules vambrace,
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlanRequest request,
      RefiningPlan plan,
      StartFailures start) {
    return from(
        rules, growthCosts, vambrace, grade, equipmentType, request, plan, null, null, start);
  }

  /**
   * optimal이 있으면 OPTIMAL 계산(단계마다 회차별 선택·기대 숨결·책·기대 비용을 붙임). ARM_GUARD면 규칙 버전·출처는 팔 보호구 단계표, grade는
   * null, 가정 문장은 팔 보호구용이다.
   */
  static RefiningPlanResponse from(
      RefiningRules rules,
      GrowthCosts growthCosts,
      VambraceRules vambrace,
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlanRequest request,
      RefiningPlan plan,
      List<RefiningPlan.OptimalStage> optimal,
      java.time.Instant pricesObservedAt,
      StartFailures start) {
    var options = request.toOptions(start.count());
    var breath = request.effectiveBreath();
    var stages = new java.util.ArrayList<StageResponse>();
    for (int i = 0; i < plan.stages().size(); i++)
      stages.add(
          StageResponse.from(
              plan.stages().get(i),
              optimal == null ? null : optimal.get(i),
              i == 0 ? start.count() : 0));
    boolean armGuard = equipmentType == RefiningRules.EquipmentType.ARM_GUARD;
    return new RefiningPlanResponse(
        armGuard ? vambrace.ruleVersion() : rules.ruleVersion(),
        armGuard
            // 팔 보호구 단계표는 커밋·라이선스가 있는 공개 데이터가 아니다(비교 기준 원본 표 대조, 내부 기록).
            ? new SourceResponse(
                vambrace.source().name(),
                "-",
                "-",
                vambrace.source().note(),
                vambrace.source().checkedAt())
            : new SourceResponse(
                rules.source().name(),
                rules.source().commit(),
                rules.source().license(),
                rules.source().crossCheckedWith(),
                rules.source().verifiedOn()),
        armGuard ? null : grade,
        equipmentType,
        options.fromStage(),
        options.toStage(),
        breath != RefiningPlanRequest.Breath.NONE,
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
        List.copyOf(stages),
        quantities(plan.expectedMaterials()),
        quantities(plan.worstCaseMaterials()),
        plan.expectedGold(),
        plan.worstCaseGold(),
        nonGold(plan),
        armGuard
            ? armGuardAssumptions(optimal != null)
            : List.of(
                "실패할 때마다 기본 확률의 10%가 오르며 최대 10회까지 누적합니다.",
                "장인의 기운은 시도 최종 성공률(숨결·책 포함) ÷ 2.15(0.01%p에서 버림)만큼 쌓이고 100%면 다음 시도가 확정 성공입니다.",
                "현재 장인의 기운·실패 횟수는 첫 단계에만 적용하고 이후 단계는 0에서 시작합니다.",
                options.growthCost()
                    ? "장비 성장(경험치) 비용은 단계마다 한 번 드는 운명의 파편을 재료에 더합니다(2026-10-10 확인). 실링·한계 해방의 고통의 가시는 골드로 바꾸지 않고 수량으로만 보여 줍니다."
                    : "장비 성장(경험치) 비용은 넣지 않았습니다.",
                options.growthCost() && options.expResearch()
                    ? "영지 연구 '"
                        + growthCosts.research().text()
                        + "'를 적용했습니다. 이 연구를 하지 않았으면 끄고 계산하세요."
                    : "영지 연구(성장 경험치 감소)는 적용하지 않았습니다.",
                "전율 목표 21단계는 일반 성장 대신 한계 해방으로 올라갑니다. 방어구는 해방 비용(운명의 파편 72,000·실링 720,000·고통의 가시 15, 공지 13339·게임 가이드)을 그 단계 성장 비용으로 넣고, 무기는 공식 수치를 확인하기 전이라 넣지 않습니다.",
                "강화 재봉술·야금술, 운명의 전율 책, 이벤트·익스프레스 추가 확률, 특수 재련은 계산하지 않습니다.",
                optimal == null
                    ? "골드 환산 시세는 포함하지 않습니다. 재료 견적 API로 기대·최악 수량을 환산할 수 있습니다."
                    : "OPTIMAL은 지금 재료 1개 즉시 구매 최저가로 시도마다 숨결(과 책) 사용을 골라 기대 비용이 가장 작은 방법입니다. 단계 expectedCostGold에 시세 환산 기대 비용이 있고, 시도 수·수량은 그 방법 기준입니다."),
        breath.name(),
        pricesObservedAt,
        optimal == null ? null : totalCostGold(optimal),
        start.from());
  }

  /** 팔 보호구(완갑) 계획의 계산 가정. */
  private static List<String> armGuardAssumptions(boolean optimal) {
    return List.of(
        "팔 보호구(완갑)는 등급 구분 없이 단계표 하나(목표 1~25단계)로 계산합니다.",
        "실패할 때마다 기본 확률의 10%가 오르며 최대 10회까지 누적합니다.",
        "장인의 기운은 시도 최종 성공률(숨결 포함) ÷ 2.15(0.01%p에서 버림)만큼 쌓이고 100%면 다음 시도가 확정 성공입니다.",
        "현재 장인의 기운·실패 횟수는 첫 단계에만 적용하고 이후 단계는 0에서 시작합니다.",
        "숨결은 용암의 숨결·빙하의 숨결 두 가지(같은 증가량)이고 책은 없습니다. 성장 지원(골드 감소)·장비 성장(경험치) 비용·영지 연구는 없습니다.",
        "이벤트·익스프레스 추가 확률은 계산하지 않습니다.",
        optimal
            ? "OPTIMAL은 지금 재료 1개 즉시 구매 최저가로 시도마다 숨결 개수를 골라(같은 개수면 싼 숨결부터) 기대 비용이 가장 작은 방법입니다."
            : "골드 환산 시세는 포함하지 않습니다. 재료 견적 API로 기대·최악 수량을 환산할 수 있습니다.");
  }

  /** 단계 기대 비용(올리기 전)의 합을 한 번 올린다. */
  static long totalCostGold(List<RefiningPlan.OptimalStage> optimal) {
    double total = 0;
    for (var stage : optimal) total += stage.expectedCostGold();
    return (long) Math.ceil(total);
  }

  /** 단계 성장 비용의 실링·한계 해방 재료를 이름별로 더한다(실링 먼저). */
  private static List<QuantityResponse> nonGold(RefiningPlan plan) {
    var totals = new java.util.LinkedHashMap<String, BigDecimal>();
    for (var stage : plan.stages()) {
      if (stage.growth() == null) continue;
      if (stage.growth().silver() > 0)
        totals.merge(
            RefiningPlan.SILVER, BigDecimal.valueOf(stage.growth().silver()), BigDecimal::add);
      for (var extra : stage.growth().extras())
        totals.merge(extra.name(), extra.amount(), BigDecimal::add);
    }
    return totals.entrySet().stream()
        .map(entry -> new QuantityResponse(entry.getKey(), entry.getValue()))
        .toList();
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
          GrowthResponse growth,
      @Schema(
              description =
                  "OPTIMAL: 실패가 이어질 때 회차별 선택(같은 선택이 이어지는 회차 묶음, 회차는 1부터 양끝 포함, breaths 0 = 숨결 없음). 장인의 기운으로 확정되는 마지막 회차는 넣지 않는다. 다른 방식이면 null")
          @com.loakraken.common.api.NullableResponse
          List<BreathRunResponse> breathPath,
      @Schema(description = "OPTIMAL: 기대 숨결 개수. 다른 방식이면 null")
          @com.loakraken.common.api.NullableResponse
          BigDecimal expectedBreaths,
      @Schema(description = "OPTIMAL: 기대 책 권수. 다른 방식이면 null")
          @com.loakraken.common.api.NullableResponse
          BigDecimal expectedBooks,
      @Schema(
              description =
                  "OPTIMAL: 그 방법의 기대 비용(골드, 올림) = 재련 골드(업화 18단계까지 성장 지원 20% 적용) + 재료·파편 × 1개 즉시 구매 최저가 + 숨결·책 × 1개 값. 운명의 파편 1개 값은 거래소 파편 주머니(소 1000·중 2000·대 3000개) 가운데 1개당 가장 싼 값. 장비 성장(경험치) 비용·실링은 넣지 않는다. 다른 방식이면 null")
          @com.loakraken.common.api.NullableResponse
          Long expectedCostGold,
      @Schema(description = "이 단계를 시작할 때 이미 실패한 횟수(첫 단계 = 요청 값이나 성공 확률로 정한 값, 다음 단계는 0)")
          @com.loakraken.common.api.NullableResponse
          Integer startFailedAttempts) {
    static StageResponse from(
        RefiningPlan.StagePlan stage, RefiningPlan.OptimalStage optimal, int startFailedAttempts) {
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
                  stage.growth().research(),
                  quantities(stage.growth().extras())),
          optimal == null
              ? null
              : optimal.breathPath().stream()
                  .map(
                      run ->
                          new BreathRunResponse(
                              run.fromAttempt(), run.toAttempt(), run.breaths(), run.book()))
                  .toList(),
          optimal == null ? null : scale2(optimal.expectedBreaths()),
          optimal == null ? null : scale2(optimal.expectedBooks()),
          // 표시 기대 비용은 올림: 비교 기준 B의 스펙업 재련 비용이 기대값을 올린 값과 12줄 모두 같았다(계산 검증 10-25).
          // 스펙업 재련 비용(SpecUpCost.refineExpected)도 같은 올림이라 두 화면 금액이 같다.
          optimal == null ? null : (long) Math.ceil(optimal.expectedCostGold()),
          startFailedAttempts);
    }
  }

  public record BreathRunResponse(
      @Schema(description = "묶음 첫 회차(1부터)") int fromAttempt,
      @Schema(description = "묶음 마지막 회차(포함)") int toAttempt,
      @Schema(description = "회차마다 쓰는 숨결 개수(0 = 없음)") int breaths,
      @Schema(description = "책 사용") boolean book) {}

  private static BigDecimal scale2(double value) {
    return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP);
  }

  public record GrowthResponse(
      @Schema(description = "성장 운명의 파편(영지 연구 적용 뒤). 재료 합계의 운명의 파편에 들어 있다") BigDecimal shard,
      @Schema(description = "성장 실링(수량 표시용, 합계에 넣지 않음)") long silver,
      @Schema(description = "false면 값이 확정되지 않아 계산에서 뺀 단계(shard 0). 2026-10-10 기준 해당 단계 없음")
          boolean confirmed,
      @Schema(description = "영지 연구(× 0.8)를 적용했는지") boolean research,
      @Schema(
              description =
                  "파편·실링 말고 이 단계에 드는 재료(전율 방어구 21단계 한계 해방의 고통의 가시 15 — 거래 불가, 골드로 바꾸지 않고 재료 합계에도 넣지 않음). 없으면 빈 목록")
          List<QuantityResponse> extraMaterials) {}

  public record GrowthSourceResponse(
      String ruleVersion,
      @Schema(description = "성장 비용 출처") String name,
      @Schema(description = "교차 확인") String crossCheckedWith,
      String verifiedOn,
      @Schema(description = "영지 연구 공식 문구(재련 가이드)") String researchText) {}

  public record QuantityResponse(String name, BigDecimal amount) {}
}
