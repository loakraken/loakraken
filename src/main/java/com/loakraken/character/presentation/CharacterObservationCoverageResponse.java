package com.loakraken.character.presentation;

import com.loakraken.character.domain.CharacterObservationCoverage;
import com.loakraken.character.domain.ObservationState;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "정규화 전 원천 제공 상태. 빈 목록은 미착용을 뜻하지 않습니다. 누락 필드와 명시적 null은 NOT_PROVIDED입니다.")
public record CharacterObservationCoverageResponse(
    ObservationState equipment,
    ObservationSectionResponse engravings,
    ObservationSectionResponse gems,
    ObservationSectionResponse cards,
    ObservationSectionResponse arkPassive,
    ObservationSectionResponse arkGrid,
    @Schema(description = "원정대 목록 상태. includeSiblings=false 요청에서는 조회하지 않아 null입니다.")
        @NullableResponse
        ObservationState siblings) {
  static CharacterObservationCoverageResponse from(CharacterObservationCoverage value) {
    return new CharacterObservationCoverageResponse(
        value.equipment(),
        ObservationSectionResponse.from(value.engravings()),
        ObservationSectionResponse.from(value.gems()),
        ObservationSectionResponse.from(value.cards()),
        ObservationSectionResponse.from(value.arkPassive()),
        ObservationSectionResponse.from(value.arkGrid()),
        value.siblings());
  }

  public record ObservationSectionResponse(
      @Schema(description = "객체 섹션은 PROVIDED 또는 NOT_PROVIDED이며, 빈 객체도 PROVIDED입니다.")
          ObservationState state,
      @Schema(
              description =
                  "섹션의 최상위 목록별 상태. engravings: slots/effects/arkPassiveEffects, gems: items, cards: items/effects, arkPassive: points/effects, arkGrid: slots/effects. 정해진 키는 항상 포함합니다.")
          Map<String, ObservationState> collections) {
    static ObservationSectionResponse from(CharacterObservationCoverage.Section value) {
      return new ObservationSectionResponse(value.state(), value.collections());
    }
  }
}
