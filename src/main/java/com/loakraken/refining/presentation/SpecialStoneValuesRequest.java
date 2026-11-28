package com.loakraken.refining.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.loakraken.refining.domain.RefiningRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 특수 재련 돌파석 가치 요청(M42, 계약 초안 'special-stone-values' 2026-11-22). */
public record SpecialStoneValuesRequest(
    @NotEmpty
        @Size(max = 28)
        @Schema(description = "대상 1~28개(무기·방어구 × 14단계 = 28이 전체 순위). 같은 대상이 두 번이면 400")
        List<@Valid @NotNull SpecialStoneTargetRequest> targets,
    @Schema(description = "재료 값 기준(재료 시세 API의 priceBasis와 같음). 생략하면 CURRENT_MIN_PRICE")
        com.loakraken.market.domain.MaterialPriceBasis priceBasis,
    @Size(max = 32) @Schema(description = "사용자 가격(그 이름의 시세만 덮음, 모든 대상에 같이). 요청한 단계가 쓰지 않는 이름이면 400")
        List<@Valid @NotNull SpecialStonePriceRequest> prices) {

  @AssertTrue(message = "같은 대상이 두 번 있습니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isTargetsDistinct() {
    return targets == null || targets.stream().distinct().count() == targets.size();
  }

  @AssertTrue(message = "같은 이름의 사용자 가격이 두 번 있습니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isPriceNamesDistinct() {
    return prices == null
        || prices.stream().map(SpecialStonePriceRequest::name).distinct().count() == prices.size();
  }

  @JsonIgnore
  com.loakraken.market.domain.MaterialPriceBasis effectivePriceBasis() {
    return priceBasis == null
        ? com.loakraken.market.domain.MaterialPriceBasis.CURRENT_MIN_PRICE
        : priceBasis;
  }

  public record SpecialStoneTargetRequest(
      @NotNull
          @Schema(
              description = "UPHWA는 받지만 그 줄은 UNSUPPORTED(SPECIAL_REFINE_UNSOURCED)",
              example = "JEONYUL")
          RefiningRules.Grade grade,
      @NotNull @Schema(description = "ARM_GUARD는 받지만 그 줄은 UNSUPPORTED", example = "WEAPON")
          RefiningRules.EquipmentType equipmentType,
      @NotNull
          @Min(10)
          @Max(24)
          @Schema(
              description =
                  "현재 재련 단계(10~24). 값은 fromStage → fromStage + 1 한 단계의 것(/refining/plans fromStage와 같은 뜻)",
              example = "20")
          Integer fromStage) {}

  public record SpecialStonePriceRequest(
      @NotBlank @Size(max = 32) @Schema(description = "재료 이름(재료 시세 API와 같은 이름)") String name,
      @NotNull
          @DecimalMin("0")
          @DecimalMax("1000000000")
          @Digits(integer = 10, fraction = 2)
          @Schema(description = "1개 골드(소수 둘째 자리까지, 0~10억)", example = "14")
          BigDecimal unitPriceGold) {}
}
