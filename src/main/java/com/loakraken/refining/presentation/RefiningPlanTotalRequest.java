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

/** 여러 부위 재련 계획 합계(#764). 공통 옵션은 /plans와 같은 뜻이고 부위마다 단계·장인의 기운만 다르다. */
public record RefiningPlanTotalRequest(
    @Schema(description = "숨결 사용 방식(/plans의 breath와 같음). 생략하면 NONE", example = "OPTIMAL")
        RefiningPlanRequest.Breath breath,
    @Schema(
            description =
                "재료 값 기준(재료 시세 API의 priceBasis와 같음). 생략하면 CURRENT_MIN_PRICE. OPTIMAL 계획 풀이는 이 값과 상관없이 즉시 구매 최저가로 한다(/plans와 같음)",
            example = "CURRENT_MIN_PRICE")
        com.loakraken.market.domain.MaterialPriceBasis priceBasis,
    @Schema(description = "/plans의 useBook과 같음. 생략하면 true", defaultValue = "true") Boolean useBook,
    @Schema(description = "/plans의 growthSupport와 같음. 생략하면 true", defaultValue = "true")
        Boolean growthSupport,
    @Schema(description = "/plans의 growthCost와 같음. 생략하면 false", defaultValue = "false")
        Boolean growthCost,
    @Schema(description = "/plans의 expResearch와 같음. 생략하면 true", defaultValue = "true")
        Boolean expResearch,
    @NotEmpty
        @Size(max = 6)
        @Schema(description = "부위(1~6개, 요청 순서대로 응답 parts에 나옴). 같은 종류가 여러 개여도 된다(방어구 5부위)")
        List<@Valid @NotNull Part> parts,
    @Size(max = 30)
        @Schema(
            description = "가진 재료(선택). 이름은 expectedMaterials[].name과 같게. 계획은 바꾸지 않고 사야 할 수량에서만 뺀다")
        List<@Valid @NotNull Owned> owned) {

  public record Part(
      @NotNull @Schema(example = "JEONYUL") RefiningRules.Grade grade,
      @NotNull @Schema(example = "ARMOR") RefiningRules.EquipmentType equipmentType,
      @NotNull @Min(10) @Max(24) @Schema(example = "19") Integer fromStage,
      @NotNull @Min(11) @Max(25) @Schema(example = "21") Integer toStage,
      @DecimalMin("0")
          @DecimalMax("100")
          @Digits(integer = 3, fraction = 2)
          @Schema(description = "첫 단계의 현재 장인의 기운(%)", defaultValue = "0")
          BigDecimal currentArtisanEnergy,
      @Min(0) @Max(10) @Schema(description = "첫 단계에서 이미 실패한 횟수", defaultValue = "0")
          Integer currentFailedAttempts) {
    @AssertTrue(message = "목표 단계는 현재 단계보다 커야 합니다.")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isValidRange() {
      return fromStage == null || toStage == null || toStage > fromStage;
    }
  }

  public record Owned(
      @NotBlank @Size(max = 40) @Schema(example = "운명의 수호석") String name,
      @NotNull @Min(0) @Max(1_000_000_000L) @Schema(description = "가진 수량", example = "12000")
          Long amount) {}

  @JsonIgnore
  com.loakraken.market.domain.MaterialPriceBasis effectivePriceBasis() {
    return priceBasis == null
        ? com.loakraken.market.domain.MaterialPriceBasis.CURRENT_MIN_PRICE
        : priceBasis;
  }

  @JsonIgnore
  RefiningPlanRequest.Breath effectiveBreath() {
    return breath == null ? RefiningPlanRequest.Breath.NONE : breath;
  }

  /** 부위 하나를 /plans 요청으로(공통 옵션을 붙여) 바꾼다. useBreath는 보내지 않는다(breath가 정함). */
  RefiningPlanRequest toPlanRequest(Part part) {
    return new RefiningPlanRequest(
        part.grade(),
        part.equipmentType(),
        part.fromStage(),
        part.toStage(),
        null,
        useBook,
        growthSupport,
        part.currentArtisanEnergy(),
        part.currentFailedAttempts(),
        growthCost,
        expResearch,
        effectiveBreath());
  }
}
