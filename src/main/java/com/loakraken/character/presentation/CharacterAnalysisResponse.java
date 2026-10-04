package com.loakraken.character.presentation;

import com.loakraken.character.domain.CharacterAnalysis;
import com.loakraken.character.domain.CharacterDiagnostics;
import com.loakraken.character.domain.CharacterObservationCoverage;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "현재 관측값의 집계입니다. 성능 점수나 효율 평가가 아니며 미제공 섹션은 null입니다.")
public record CharacterAnalysisResponse(
    int equipmentCount,
    @NullableResponse GemSummaryResponse gems,
    @NullableResponse CardSummaryResponse cards,
    @NullableResponse EngravingSummaryResponse engravings,
    @NullableResponse PassiveSummaryResponse arkPassive,
    @NullableResponse GridSummaryResponse arkGrid,
    CharacterDiagnosticsResponse diagnostics) {
  static CharacterAnalysisResponse from(
      CharacterAnalysis value, CharacterObservationCoverage coverage) {
    var gems = value.gems();
    var cards = value.cards();
    var engravings = value.engravings();
    var passive = value.arkPassive();
    var grid = value.arkGrid();
    return new CharacterAnalysisResponse(
        value.equipmentCount(),
        gems == null
            ? null
            : new GemSummaryResponse(
                gems.count(),
                gems.unknownLevelCount(),
                gems.minLevel(),
                gems.maxLevel(),
                gems.averageLevel(),
                gems.levelDistribution().stream()
                    .map(level -> new LevelCountResponse(level.level(), level.count()))
                    .toList()),
        cards == null
            ? null
            : new CardSummaryResponse(
                cards.count(), cards.unknownAwakeningCount(), cards.knownAwakeningTotal()),
        engravings == null
            ? null
            : new EngravingSummaryResponse(
                engravings.slotCount(),
                engravings.legacyEffectCount(),
                engravings.arkPassiveEffectCount()),
        passive == null
            ? null
            : new PassiveSummaryResponse(
                passive.enabled(),
                passive.points().stream()
                    .map(point -> new PointValueResponse(point.name(), point.value()))
                    .toList()),
        grid == null
            ? null
            : new GridSummaryResponse(
                grid.coreCount(),
                grid.activeGemCount(),
                grid.inactiveGemCount(),
                grid.unknownGemCount()),
        CharacterDiagnosticsResponse.from(CharacterDiagnostics.from(value, coverage)));
  }

  public record GemSummaryResponse(
      int count,
      int unknownLevelCount,
      @NullableResponse Integer minLevel,
      @NullableResponse Integer maxLevel,
      @Schema(description = "확인 가능한 양수 레벨만 산술 평균, 소수 둘째 자리 반올림. 계산할 값이 없으면 null입니다.")
          @NullableResponse
          BigDecimal averageLevel,
      List<LevelCountResponse> levelDistribution) {}

  public record LevelCountResponse(int level, int count) {}

  public record CardSummaryResponse(
      int count,
      int unknownAwakeningCount,
      @Schema(description = "확인 가능한 카드 각성 수의 합. 세트 효과나 성능 점수가 아닙니다.") long knownAwakeningTotal) {}

  public record EngravingSummaryResponse(
      int slotCount, int legacyEffectCount, int arkPassiveEffectCount) {}

  public record PassiveSummaryResponse(
      @NullableResponse Boolean enabled, List<PointValueResponse> points) {}

  public record PointValueResponse(
      @NullableResponse String name, @NullableResponse Integer value) {}

  public record GridSummaryResponse(
      int coreCount, int activeGemCount, int inactiveGemCount, int unknownGemCount) {}
}
