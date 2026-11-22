package com.loakraken.refining.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.refining.domain.AdvancedRefineChain;
import com.loakraken.refining.domain.AdvancedRefineCost;
import com.loakraken.refining.domain.AdvancedRefineRules;
import com.loakraken.refining.domain.RefiningRules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(description = "상급 재련 기대 비용. 10단계 구간마다 기대 턴·숨결·책 선택·재료·골드이고, 기대값은 확률 분포의 평균이다(중앙값·90% 없음).")
public record AdvancedRefiningPlanResponse(
    @Schema(description = "상급 재련 규칙 버전") String ruleVersion,
    AdvancedRefiningSourceResponse source,
    RefiningRules.Grade grade,
    RefiningRules.EquipmentType equipmentType,
    int fromStage,
    int toStage,
    @Schema(description = "지나는 10단계 구간마다 한 줄(차례대로)") List<AdvancedRefiningBandResponse> bands,
    @Schema(description = "지나는 담금질 단계(10·20·30에서 다음으로 갈 때). 담금질 재료는 귀속이라 금액에 넣지 않는다. 없으면 빈 목록")
        List<Integer> temperingStages,
    @Schema(description = "구간 기대 재료의 합(소수 둘째 자리 반올림)")
        List<RefiningPlanResponse.QuantityResponse> expectedMaterials,
    @Schema(description = "구간 재련 골드의 합(재료 값 제외, 소수 둘째 자리 반올림)") BigDecimal expectedRefineGold,
    @Schema(description = "구간 기대 실링의 합(수량 표시용, 골드에 넣지 않음). 어느 구간이라도 실링 값이 없으면 null")
        @NullableResponse
        BigDecimal expectedSilver,
    @Schema(
            description =
                "계획 전체 기대 비용(골드) = 올리기 전 구간 값(재료 × priceBasis 값 + 재련 골드)의 합을 한 번 올림. 스펙업 상급 재련 단계 비용(ADVANCED_REFINE_EXPECTED)과 같은 계산")
        long expectedCostGold,
    @Schema(description = "계산에 쓴 재료 시세 관측 시각") Instant pricesObservedAt,
    @Schema(
            description = "재료 값 기준. CURRENT_MIN_PRICE = 1개 즉시 구매 최저가",
            example = "CURRENT_MIN_PRICE")
        String priceBasis,
    @Schema(description = "숨결·책 가운데 시세가 없는 재료가 있어 그 재료를 쓰는 선택을 빼고 골랐는지(시세가 다 있으면 비용이 더 작을 수 있음)")
        boolean policyLimited,
    @Schema(description = "출처가 한 곳뿐인 값을 썼는지(규칙 파일 표시, 21단계부터 강화 선조 확률 포함)") boolean singleSource,
    @Schema(description = "계산 가정과 제외 항목") List<String> assumptions) {

  static final String PRICE_BASIS = "CURRENT_MIN_PRICE";

  static AdvancedRefiningPlanResponse from(
      AdvancedRefineRules rules,
      AdvancedRefiningPlanRequest request,
      AdvancedRefineCost.Result result,
      Instant pricesObservedAt) {
    var refineGold = BigDecimal.ZERO;
    var bands = new java.util.ArrayList<AdvancedRefiningBandResponse>();
    for (var band : result.bands()) {
      refineGold = refineGold.add(band.gold());
      bands.add(AdvancedRefiningBandResponse.from(rules, band));
    }
    return new AdvancedRefiningPlanResponse(
        rules.ruleVersion(),
        AdvancedRefiningSourceResponse.from(rules.source()),
        request.grade(),
        request.equipmentType(),
        request.fromStage(),
        request.toStage(),
        List.copyOf(bands),
        result.tempering(),
        quantities(result.materials()),
        scale2(refineGold),
        result.silver() == null ? null : scale2(result.silver()),
        result.expectedGold().setScale(0, RoundingMode.CEILING).longValueExact(),
        pricesObservedAt,
        PRICE_BASIS,
        result.policyLimited(),
        result.singleSource(),
        List.of(
            "구간(0~10·10~20·20~30·30~40)마다 정확히 풀었습니다. 시작은 현재 단계의 경험치 0, 구슬 0입니다(공식 API는 단계만 줍니다).",
            "숨결·책 사용은 지금 재료 1개 즉시 구매 최저가 기준으로 기대 비용이 가장 작은 조합을 골랐습니다(다른 시세 기준으로 다시 고르지 않습니다).",
            "성장 지원(1~20단계)은 재료·숨결 −70%, 파편·실링 −90%, 골드 −50%(각각 올림)이고 책은 할인하지 않습니다.",
            "10·20·30단계 담금질 재료는 귀속이라 금액에 넣지 않았습니다.",
            "실링은 골드로 바꾸지 않고 수량으로만 보여 줍니다."));
  }

  public record AdvancedRefiningSourceResponse(
      @Schema(description = "출처 이름") String name,
      @Schema(description = "공식 가이드(주소·확인일)") String officialGuide,
      @Schema(description = "공식 공지(주소·확인일)") String officialNotice,
      String verifiedOn) {
    static AdvancedRefiningSourceResponse from(Map<String, Object> source) {
      return new AdvancedRefiningSourceResponse(
          "공식 가이드·공지(상급 재련)",
          text(source, "officialGuide"),
          text(source, "officialNotice"),
          text(source, "verifiedOn"));
    }

    private static String text(Map<String, Object> source, String key) {
      var value = source.get(key);
      return value == null ? "" : value.toString();
    }
  }

  public record AdvancedRefiningBandResponse(
      @Schema(description = "구간 시작 단계(0·10·20·30)") int bandStart,
      @Schema(description = "이 구간에서 시작하는 단계") int fromStage,
      @Schema(description = "이 구간에서 끝나는 단계") int toStage,
      AdvancedRefiningTurnsResponse expectedTurns,
      AdvancedRefiningPolicyResponse policy,
      @Schema(description = "성장 지원(1~20단계 할인)을 적용했는지") boolean growthSupport,
      @Schema(description = "기대 재료 개수(기본 재료는 유료·선조·강화 선조 턴, 숨결·책은 고른 선택대로, 소수 둘째 자리 반올림)")
          List<RefiningPlanResponse.QuantityResponse> expectedMaterials,
      @Schema(description = "기대 재련 골드(재료 값 제외)") BigDecimal expectedRefineGold,
      @Schema(description = "기대 실링(수량 표시용). 이 구간 실링 값이 없으면 null") @NullableResponse
          BigDecimal expectedSilver,
      @Schema(description = "이 구간 기대 비용(골드, 올림) = 재료 × priceBasis 값 + 재련 골드") long expectedCostGold,
      @Schema(description = "이 구간에 출처가 한 곳뿐인 값이 있는지") boolean singleSource) {
    static AdvancedRefiningBandResponse from(
        AdvancedRefineRules rules, AdvancedRefineCost.BandResult band) {
      boolean high = band.bandStart() >= 20;
      var turns = band.turns();
      return new AdvancedRefiningBandResponse(
          band.bandStart(),
          band.fromStage(),
          band.toStage(),
          new AdvancedRefiningTurnsResponse(
              scale2(turns.paid()),
              scale2(turns.free()),
              scale2(turns.ancestor()),
              high ? scale2(turns.enhanced()) : null,
              scale2(turns.total())),
          AdvancedRefiningPolicyResponse.from(band.policy(), high),
          rules.supported(band.bandStart()),
          quantities(band.materials()),
          scale2(band.gold()),
          band.silver() == null ? null : scale2(band.silver()),
          band.expectedGold().setScale(0, RoundingMode.CEILING).longValueExact(),
          band.singleSource());
    }
  }

  @Schema(description = "턴 종류별 기대 턴 수(소수 둘째 자리 반올림)")
  public record AdvancedRefiningTurnsResponse(
      @Schema(description = "유료 일반 턴") BigDecimal paid,
      @Schema(description = "무료 일반 턴(테메르)") BigDecimal free,
      @Schema(description = "선조 턴") BigDecimal ancestor,
      @Schema(description = "강화 선조 턴. 20단계 아래 구간은 null") @NullableResponse BigDecimal enhanced,
      @Schema(description = "합") BigDecimal total) {}

  @Schema(description = "턴 종류마다 고른 선택: NONE(없음)·BREATH(숨결)·BOOK(책)·BOTH(둘 다). 모르는 값은 코드 그대로 보인다")
  public record AdvancedRefiningPolicyResponse(
      String normal, String ancestor, @NullableResponse String enhanced) {
    static AdvancedRefiningPolicyResponse from(AdvancedRefineChain.Policy policy, boolean high) {
      return new AdvancedRefiningPolicyResponse(
          choice(policy.normal()),
          choice(policy.ancestor()),
          high ? choice(policy.enhanced()) : null);
    }

    /** K 비트: 1 = 숨결, 2 = 책. */
    static String choice(int k) {
      return switch (k) {
        case 0 -> "NONE";
        case 1 -> "BREATH";
        case 2 -> "BOOK";
        default -> "BOTH";
      };
    }
  }

  private static List<RefiningPlanResponse.QuantityResponse> quantities(
      Map<String, BigDecimal> values) {
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                new RefiningPlanResponse.QuantityResponse(entry.getKey(), scale2(entry.getValue())))
        .toList();
  }

  private static BigDecimal scale2(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal scale2(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }
}
