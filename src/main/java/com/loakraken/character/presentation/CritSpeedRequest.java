package com.loakraken.character.presentation;

import com.loakraken.character.domain.CritSpeedCalculation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CritSpeedRequest(
    @Schema(description = "계산할 캐릭터명", example = "모험가", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "\\s*[\\p{L}\\p{N}]+\\s*")
        String characterName,
    @Schema(description = "파티·버프·조건 가정. 생략하면 모든 가정 없음(관측값만)") @Valid Assumptions assumptions) {

  @Schema(name = "CritSpeedAssumptions", description = "% 단위 가정. 생략한 값은 0 또는 false")
  public record Assumptions(
      @Schema(description = "백어택 적중 가정(치명타 적중률 +10%)") Boolean backAttack,
      @Schema(description = "치명 펫 효과(치명 +160)") Boolean critPet,
      @Schema(description = "치명타 적중률 시너지 수(동일 직업 제외, 1개당 +10%)") @Min(0) @Max(3)
          Integer critSynergyCount,
      @Schema(description = "서포터 치명타 적중률 버프 %")
          @DecimalMin("0")
          @DecimalMax("30")
          @Digits(integer = 3, fraction = 2)
          BigDecimal supportCritRate,
      @Schema(description = "사용 스킬 트라이포드 치명타 적중률 %")
          @DecimalMin("0")
          @DecimalMax("100")
          @Digits(integer = 3, fraction = 2)
          BigDecimal tripodCritRate,
      @Schema(description = "기타 치명타 적중률 가정 %(아크 그리드 코어 등)")
          @DecimalMin("0")
          @DecimalMax("100")
          @Digits(integer = 3, fraction = 2)
          BigDecimal extraCritRate,
      @Schema(description = "공격 속도 가정 %(직업 각인·트라이포드·서포터 버프·만찬·음식 합)")
          @DecimalMin("0")
          @DecimalMax("100")
          @Digits(integer = 3, fraction = 2)
          BigDecimal extraAttackSpeed,
      @Schema(description = "이동 속도 가정 %(직업 각인·트라이포드·서포터 버프·만찬·음식 합)")
          @DecimalMin("0")
          @DecimalMax("100")
          @Digits(integer = 3, fraction = 2)
          BigDecimal extraMoveSpeed) {}

  CritSpeedCalculation.Assumptions toAssumptions() {
    if (assumptions == null) return CritSpeedCalculation.Assumptions.NONE;
    return new CritSpeedCalculation.Assumptions(
        Boolean.TRUE.equals(assumptions.backAttack()),
        Boolean.TRUE.equals(assumptions.critPet()),
        assumptions.critSynergyCount() == null ? 0 : assumptions.critSynergyCount(),
        assumptions.supportCritRate(),
        assumptions.tripodCritRate(),
        assumptions.extraCritRate(),
        assumptions.extraAttackSpeed(),
        assumptions.extraMoveSpeed());
  }

  String normalizedName() {
    return characterName.strip();
  }
}
