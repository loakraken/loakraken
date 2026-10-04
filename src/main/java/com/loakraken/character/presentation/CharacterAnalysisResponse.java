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
    EquipmentSummaryResponse equipment,
    @NullableResponse GemSummaryResponse gems,
    @NullableResponse CardSummaryResponse cards,
    @NullableResponse EngravingSummaryResponse engravings,
    @NullableResponse PassiveSummaryResponse arkPassive,
    @NullableResponse GridSummaryResponse arkGrid,
    CharacterDiagnosticsResponse diagnostics) {
  static CharacterAnalysisResponse from(
      CharacterAnalysis value, CharacterObservationCoverage coverage) {
    var equipment = value.equipment();
    var gems = value.gems();
    var cards = value.cards();
    var engravings = value.engravings();
    var passive = value.arkPassive();
    var grid = value.arkGrid();
    return new CharacterAnalysisResponse(
        value.equipmentCount(),
        new EquipmentSummaryResponse(
            equipment.detailCount(),
            equipment.detailUnavailableCount(),
            equipment.refineCount(),
            equipment.minRefineLevel(),
            equipment.maxRefineLevel(),
            equipment.refineDistribution().stream()
                .map(level -> new LevelCountResponse(level.level(), level.count()))
                .toList(),
            equipment.qualityCount(),
            equipment.minQuality(),
            equipment.maxQuality(),
            equipment.averageQuality(),
            equipment.arkPassivePoints().stream()
                .map(point -> new PointValueResponse(point.name(), point.value()))
                .toList()),
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

  @Schema(description = "장비 Tooltip 해석 결과의 관측 집계입니다. 부위별 가중치·점수·추천이 아니며 값이 없는 장비는 각 집계에서 제외합니다.")
  public record EquipmentSummaryResponse(
      @Schema(description = "Tooltip을 해석한 장비 수") int detailCount,
      @Schema(description = "Tooltip이 없거나 해석할 수 없어 detail이 null인 장비 수") int detailUnavailableCount,
      @Schema(description = "재련 단계가 확인된 장비 수") int refineCount,
      @NullableResponse Integer minRefineLevel,
      @NullableResponse Integer maxRefineLevel,
      List<LevelCountResponse> refineDistribution,
      @Schema(description = "품질이 확인된 장비 수. 품질이 적용되지 않는 장비는 제외") int qualityCount,
      @NullableResponse Integer minQuality,
      @NullableResponse Integer maxQuality,
      @Schema(description = "확인된 품질의 단순 산술 평균, 소수 둘째 자리 반올림. 값이 없으면 null입니다.") @NullableResponse
          BigDecimal averageQuality,
      @Schema(description = "장비가 제공하는 아크패시브 포인트의 이름별 합계. 아크패시브 섹션의 전체 포인트와 다릅니다.")
          List<PointValueResponse> arkPassivePoints) {}

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
