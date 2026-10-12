package com.loakraken.receipt.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.receipt.application.ReceiptService;
import com.loakraken.receipt.domain.EquipmentReceipt;
import com.loakraken.receipt.domain.GemReceipt;
import com.loakraken.refining.domain.RefiningRules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "원정대 영수증(receipt-v1). 검증된 항목(장비 재련·거래 가능 보석)만 금액에 넣는다")
public record ReceiptResponse(
    String ruleVersion,
    @Schema(description = "장비 재련 계산 규칙 버전") String refiningRuleVersion,
    String serverName,
    String characterName,
    @Schema(description = "이 영수증을 계산한 시각") Instant computedAt,
    @Schema(description = "재료·보석 시세 중 가장 이른 관측 시각. 시세를 쓰지 않았으면 null") @NullableResponse
        Instant priceObservedAt,
    ReceiptTotalResponse total,
    ReceiptEquipmentResponse equipment,
    ReceiptGemsResponse gems,
    @Schema(description = "금액에 넣지 않은 분류(산정 기준 검증 중)") List<ReceiptNotIncludedResponse> notIncluded,
    ReceiptCoverageResponse coverage,
    @Schema(description = "화면에 그대로 보이는 가정 문장") List<String> assumptions) {

  static ReceiptResponse from(ReceiptService.Receipt receipt, String refiningRuleVersion) {
    return new ReceiptResponse(
        ReceiptService.RULE_VERSION,
        refiningRuleVersion,
        receipt.serverName(),
        receipt.characterName(),
        receipt.computedAt(),
        receipt.priceObservedAt(),
        new ReceiptTotalResponse(receipt.totalGold(), List.of("EQUIPMENT", "GEM")),
        new ReceiptEquipmentResponse(
            receipt.equipmentGold(),
            receipt.characters().stream().map(ReceiptCharacterResponse::from).toList()),
        ReceiptGemsResponse.from(receipt.gems()),
        receipt.notIncluded().stream()
            .map(item -> new ReceiptNotIncludedResponse(item.kind(), "VERIFYING", item.count()))
            .toList(),
        new ReceiptCoverageResponse(
            receipt.rosterCharacters(),
            receipt.ready(),
            receipt.pending(),
            receipt.nextAvailableAt()),
        ReceiptService.assumptions(receipt.assumeBook()));
  }

  public record ReceiptTotalResponse(
      @Schema(description = "포함 분류 합계(골드)") BigDecimal gold,
      @Schema(description = "금액에 넣은 분류") List<String> includedKinds) {}

  public record ReceiptEquipmentResponse(
      BigDecimal gold, List<ReceiptCharacterResponse> characters) {}

  public record ReceiptCharacterResponse(
      String characterName,
      @NullableResponse String className,
      @NullableResponse BigDecimal itemLevel,
      @Schema(description = "READY 또는 PENDING(이번 요청에서 전투정보를 아직 받지 못함)")
          ReceiptService.Status status,
      @Schema(description = "PENDING이면 null") @NullableResponse BigDecimal gold,
      List<ReceiptPartResponse> parts,
      List<ReceiptExcludedResponse> excluded) {
    static ReceiptCharacterResponse from(ReceiptService.CharacterReceipt receipt) {
      var equipment = receipt.equipment();
      return new ReceiptCharacterResponse(
          receipt.characterName(),
          receipt.className(),
          receipt.itemLevel(),
          receipt.status(),
          equipment == null ? null : equipment.gold(),
          equipment == null
              ? List.of()
              : equipment.parts().stream().map(ReceiptPartResponse::from).toList(),
          equipment == null
              ? List.of()
              : equipment.excluded().stream().map(ReceiptExcludedResponse::from).toList());
    }
  }

  public record ReceiptPartResponse(
      String slot,
      String name,
      RefiningRules.Grade grade,
      @Schema(description = "산정 시작 단계(10)") int fromStage,
      @Schema(description = "현재 단계. 10 이하면 비용 0") int toStage,
      BigDecimal gold,
      List<ReceiptStageResponse> stages) {
    static ReceiptPartResponse from(EquipmentReceipt.Part part) {
      return new ReceiptPartResponse(
          part.slot(),
          part.name(),
          part.grade(),
          part.fromStage(),
          part.toStage(),
          part.gold(),
          part.stages().stream()
              .map(
                  stage ->
                      new ReceiptStageResponse(
                          stage.stage(),
                          stage.gold(),
                          stage.breath(),
                          stage.book(),
                          stage.growthSupport()))
              .toList());
    }
  }

  public record ReceiptStageResponse(
      @Schema(description = "목표 단계") int stage,
      @Schema(description = "그 단계 기대 비용(골드)") BigDecimal gold,
      @Schema(description = "회차별 최적 전략에서 숨결을 쓰는 회차가 있음") boolean breath,
      @Schema(description = "회차별 최적 전략에서 책을 쓰는 회차가 있음") boolean book,
      @Schema(description = "성장 지원 적용(업화 18단계 이하)") boolean growthSupport) {}

  public record ReceiptExcludedResponse(
      @Schema(description = "부위. 상급 재련처럼 캐릭터 전체에 걸린 사유면 null") @NullableResponse String slot,
      @NullableResponse String name,
      @Schema(
              description =
                  "UNSUPPORTED_GRADE·UNSUPPORTED_STAGE·ARM_GUARD·ADVANCED_REFINING·PRICE_UNAVAILABLE")
          EquipmentReceipt.Reason reason,
      String message) {
    static ReceiptExcludedResponse from(EquipmentReceipt.Excluded excluded) {
      return new ReceiptExcludedResponse(
          excluded.slot(), excluded.name(), excluded.reason(), excluded.message());
    }
  }

  public record ReceiptGemsResponse(
      @Schema(description = "시세가 있는 거래 가능 보석 합계") BigDecimal gold,
      @Schema(description = "거래 가능 보석 수") int tradable,
      @Schema(description = "그중 시세로 환산한 수") int priced,
      @Schema(description = "귀속 보석 수(금액 제외)") int bound,
      @Schema(description = "시세 없음·대기로 환산하지 못한 수") int unpriced,
      List<ReceiptGemResponse> items) {
    static ReceiptGemsResponse from(GemReceipt.Result result) {
      return new ReceiptGemsResponse(
          result.gold(),
          result.tradable(),
          result.priced(),
          result.bound(),
          result.unpriced(),
          result.items().stream()
              .map(
                  item ->
                      new ReceiptGemResponse(
                          item.kind(),
                          item.level(),
                          item.count(),
                          item.unitPrice(),
                          item.gold(),
                          item.status()))
              .toList());
    }
  }

  public record ReceiptGemResponse(
      String kind,
      int level,
      int count,
      @NullableResponse BigDecimal unitPrice,
      @NullableResponse BigDecimal gold,
      @Schema(description = "PRICED·NO_PRICE(시세 표에 없는 종류)·PENDING(칸이 아직 빔)")
          GemReceipt.Status status) {}

  public record ReceiptNotIncludedResponse(
      @Schema(description = "ARK_GRID_GEM·BRACELET·ACCESSORY") String kind,
      @Schema(description = "VERIFYING(산정 기준 검증 중)") String reason,
      int count) {}

  public record ReceiptCoverageResponse(
      int rosterCharacters,
      int ready,
      @Schema(description = "아직 전투정보를 받지 못한 캐릭터 수(다시 요청하면 최대 8명씩 채움)") int pending,
      @Schema(
              description =
                  "남은 캐릭터를 이어 받을 수 있는 시각. 상한으로 멈췄으면 지금, 공식 한도·장애로 멈췄으면 1분 뒤. 남은 사람이 없으면 null")
          @NullableResponse
          Instant nextAvailableAt) {}
}
