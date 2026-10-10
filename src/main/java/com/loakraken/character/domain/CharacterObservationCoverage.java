package com.loakraken.character.domain;

import java.util.Map;
import java.util.Objects;

public record CharacterObservationCoverage(
    ObservationState equipment,
    Section engravings,
    Section gems,
    Section cards,
    Section arkPassive,
    Section arkGrid,
    ObservationState siblings,
    ObservationState skills,
    ObservationState avatars,
    ObservationState collectibles) {
  public CharacterObservationCoverage(
      ObservationState equipment,
      Section engravings,
      Section gems,
      Section cards,
      Section arkPassive,
      Section arkGrid,
      ObservationState siblings,
      ObservationState skills) {
    this(
        equipment,
        engravings,
        gems,
        cards,
        arkPassive,
        arkGrid,
        siblings,
        skills,
        ObservationState.NOT_PROVIDED,
        ObservationState.NOT_PROVIDED);
  }

  public CharacterObservationCoverage(
      ObservationState equipment,
      Section engravings,
      Section gems,
      Section cards,
      Section arkPassive,
      Section arkGrid,
      ObservationState siblings) {
    this(
        equipment,
        engravings,
        gems,
        cards,
        arkPassive,
        arkGrid,
        siblings,
        ObservationState.NOT_PROVIDED);
  }

  public CharacterObservationCoverage {
    Objects.requireNonNull(equipment);
    Objects.requireNonNull(engravings);
    Objects.requireNonNull(gems);
    Objects.requireNonNull(cards);
    Objects.requireNonNull(arkPassive);
    Objects.requireNonNull(arkGrid);
    Objects.requireNonNull(skills);
    Objects.requireNonNull(avatars);
    Objects.requireNonNull(collectibles);
  }

  /** collections는 해당 섹션의 명시적으로 매핑한 최상위 목록 상태다. */
  public record Section(ObservationState state, Map<String, ObservationState> collections) {
    public Section {
      Objects.requireNonNull(state);
      if (state == ObservationState.PROVIDED_EMPTY) {
        throw new IllegalArgumentException("객체 섹션에는 빈 목록 상태를 사용할 수 없습니다.");
      }
      collections = Map.copyOf(collections);
    }
  }
}
