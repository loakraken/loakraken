package com.loakraken.refining.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.refining.application.SpecialStoneValueService;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.refining.domain.SpecialRefineRules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(
    description =
        "특수 재련 돌파석 1개 가치. 가치 = 그 단계(fromStage → +1) 일반 재련 기대 비용(회차별 최적, 보장 시도에는 숨결 없음) ÷ 평균 필요량(1회 소모량 ÷ 공식 기본 확률). 특수 재련은 장인의 기운·실패 확률 증가가 없다(공식 가이드).")
public record SpecialStoneValuesResponse(
    @Schema(description = "재료 값 기준(요청 값)") String priceBasis,
    @Schema(description = "쓴 재료 시세의 가장 이른 관측 시각(사용자 가격은 빼고). 시세를 하나도 쓰지 않았으면 null")
        @NullableResponse
        Instant pricesObservedAt,
    @Schema(description = "일반 재련 단계표 규칙 버전") String ruleVersion,
    @Schema(description = "특수 재련 1회 소모량 규칙 버전") String specialRuleVersion,
    SpecialStonePerTrySourceResponse perTrySource,
    @Schema(description = "대상마다 한 줄(요청 순서)") List<SpecialStoneRowResponse> rows) {

  static SpecialStoneValuesResponse from(
      String priceBasis,
      Instant pricesObservedAt,
      String ruleVersion,
      SpecialRefineRules special,
      List<SpecialStoneValueService.Row> rows) {
    return new SpecialStoneValuesResponse(
        priceBasis,
        pricesObservedAt,
        ruleVersion,
        special.ruleVersion(),
        new SpecialStonePerTrySourceResponse(
            "사용자 확인 캡처(참고 자료)",
            null,
            special.source().capturedOn(),
            special.source().singleSource()),
        rows.stream().map(SpecialStoneRowResponse::from).toList());
  }

  @Schema(description = "1회 소모량 출처(출처 이름은 두지 않는다)")
  public record SpecialStonePerTrySourceResponse(
      String name,
      @Schema(description = "주소(지금은 없음)") @NullableResponse String url,
      @Schema(description = "본 날짜") String capturedOn,
      @Schema(description = "출처가 한 곳뿐인지") boolean singleSource) {}

  public record SpecialStoneRowResponse(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      int fromStage,
      @Schema(description = "fromStage + 1") int targetStage,
      @Schema(
              description = "OK · UNSUPPORTED · PRICE_MISSING(닫힌 값, 새 값은 깨는 변경)",
              allowableValues = {"OK", "UNSUPPORTED", "PRICE_MISSING"})
          String status,
      @Schema(description = "UNSUPPORTED: SPECIAL_REFINE_UNSOURCED. 다른 상태면 null") @NullableResponse
          String reasonCode,
      @Schema(description = "UNSUPPORTED·PRICE_MISSING 이유 문장. OK면 null") @NullableResponse
          String reason,
      @Schema(description = "순환·전이 돌파석 이름. 출처를 확인하기 전까지 null(웹은 '돌파석')") @NullableResponse
          String stone,
      @Schema(description = "특수 재련 1회 소모량(fromStage → +1)") @NullableResponse Integer perTry,
      @Schema(description = "공식 기본 확률(만분율, 1 = 0.01%)") @NullableResponse Integer baseRateBp,
      @Schema(description = "평균 필요량 = perTry ÷ 기본 확률(기하 분포, 보정 없음)") @NullableResponse
          BigDecimal averageNeeded,
      @Schema(description = "일반 재련 기대 시도 수(회차별 최적)") @NullableResponse
          BigDecimal expectedNormalAttempts,
      @Schema(description = "일반 재련 기대 비용(골드, 회차별 최적, 보장 시도에는 숨결 없음, 사용자 가격 또는 시세)")
          @NullableResponse
          BigDecimal expectedNormalCostGold,
      @Schema(description = "돌파석 1개 가치(골드) = expectedNormalCostGold ÷ averageNeeded(소수 첫째 자리)")
          @NullableResponse
          BigDecimal valuePerStoneGold,
      @Schema(description = "일반 재련 1회 내역") @NullableResponse
          SpecialStoneNormalTryResponse normalTry,
      @Schema(description = "PRICE_MISSING: 사용자 가격도 시세도 없는 이름. 다른 상태면 빈 목록")
          List<String> missingPrices) {
    static SpecialStoneRowResponse from(SpecialStoneValueService.Row row) {
      var normal = row.normalTry();
      return new SpecialStoneRowResponse(
          row.target().grade(),
          row.target().type(),
          row.target().fromStage(),
          row.target().fromStage() + 1,
          row.status().name(),
          row.reasonCode(),
          row.reason(),
          null,
          row.perTry(),
          row.baseRateBp(),
          row.averageNeeded(),
          row.expectedNormalAttempts(),
          row.expectedNormalCostGold(),
          row.valuePerStoneGold(),
          normal == null
              ? null
              : new SpecialStoneNormalTryResponse(
                  normal.materials().stream()
                      .map(
                          m ->
                              new SpecialStoneMaterialResponse(
                                  m.name(), m.amount(), m.unitPriceGold(), m.gold()))
                      .toList(),
                  normal.refineGold(),
                  normal.breath() == null
                      ? null
                      : new SpecialStoneBreathResponse(
                          normal.breath().name(),
                          normal.breath().max(),
                          normal.breath().unitPriceGold()),
                  normal.costWithoutBreathGold(),
                  normal.costWithMaxBreathGold()),
          row.missingPrices());
    }
  }

  public record SpecialStoneNormalTryResponse(
      List<SpecialStoneMaterialResponse> materials,
      @Schema(description = "재련 골드") long refineGold,
      @Schema(description = "숨결(최대 개수·1개 값). 그 단계에 없으면 null") @NullableResponse
          SpecialStoneBreathResponse breath,
      @Schema(description = "숨결 없이 1회 비용(골드, 반올림)") long costWithoutBreathGold,
      @Schema(description = "숨결 최대로 1회 비용. 숨결이 없으면 null") @NullableResponse
          Long costWithMaxBreathGold) {}

  public record SpecialStoneMaterialResponse(
      String name,
      @Schema(description = "1회 개수") BigDecimal amount,
      @Schema(description = "1개 골드(사용자 가격 또는 시세)") BigDecimal unitPriceGold,
      @Schema(description = "개수 × 1개 값") BigDecimal gold) {}

  public record SpecialStoneBreathResponse(
      String name, @Schema(description = "최대 개수") int max, BigDecimal unitPriceGold) {}
}
