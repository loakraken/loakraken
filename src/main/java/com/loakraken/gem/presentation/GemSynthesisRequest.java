package com.loakraken.gem.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.loakraken.gem.domain.GemSynthesisPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record GemSynthesisRequest(
    @NotNull
        @Min(4)
        @Max(4)
        @Schema(description = "현재 지원하는 보석 티어", allowableValues = "4", example = "4")
        Integer tier,
    @NotNull @Min(1) @Max(9) @Schema(description = "추가 확보량을 표현할 기준 보석 레벨", example = "7")
        Integer baseLevel,
    @NotNull @Min(2) @Max(10) @Schema(description = "기준보다 높은 목표 레벨", example = "10")
        Integer targetLevel,
    @NotNull @Min(1) @Max(1000) @Schema(description = "종류·스킬을 구분하지 않는 목표 보석 개수", example = "1")
        Long targetQuantity,
    @NotNull @Size(max = 10) @Schema(description = "합성에 사용할 수 있는 티어 4 보석. 빈 목록 가능, 누락 레벨은 0개")
        List<@NotNull @Valid GemInventoryRequest> inventory) {
  @AssertTrue(message = "기준보다 높은 목표와 범위 내 고유 보유 레벨을 지정해야 합니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isValidLevels() {
    if (baseLevel == null || targetLevel == null || inventory == null) return true;
    if (baseLevel >= targetLevel) return false;
    var levels =
        inventory.stream()
            .filter(item -> item != null && item.level() != null)
            .map(GemInventoryRequest::level)
            .toList();
    return levels.stream().allMatch(level -> level >= baseLevel && level <= targetLevel)
        && levels.stream().distinct().count() == levels.size();
  }

  public GemSynthesisPlan calculate() {
    return GemSynthesisPlan.calculate(
        baseLevel,
        targetLevel,
        targetQuantity,
        inventory.stream()
            .map(item -> new GemSynthesisPlan.OwnedGem(item.level(), item.quantity()))
            .toList());
  }

  public record GemInventoryRequest(
      @NotNull @Min(1) @Max(10) Integer level, @NotNull @Min(0) @Max(999999999) Long quantity) {}
}
