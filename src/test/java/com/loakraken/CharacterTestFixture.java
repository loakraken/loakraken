package com.loakraken;

import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterObservationCoverage;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.character.domain.ObservationState;
import java.util.List;
import java.util.Map;

/** 이 합성 fixture는 제공된 장비 목록과 미제공 나머지 섹션만 사용한다. */
public final class CharacterTestFixture {
  private CharacterTestFixture() {}

  public static CharacterDetails details(
      CharacterProfile profile, CharacterLoadout loadout, List<CharacterSibling> siblings) {
    return new CharacterDetails(
        profile,
        loadout,
        siblings,
        new CharacterObservationCoverage(
            ObservationState.collection(loadout.equipment()),
            missing("slots", "effects", "arkPassiveEffects"),
            missing("items"),
            missing("items", "effects"),
            missing("points", "effects"),
            missing("slots", "effects"),
            siblings == null ? null : ObservationState.collection(siblings)));
  }

  private static CharacterObservationCoverage.Section missing(String... keys) {
    var values = new java.util.HashMap<String, ObservationState>();
    for (var key : keys) values.put(key, ObservationState.NOT_PROVIDED);
    return new CharacterObservationCoverage.Section(
        ObservationState.NOT_PROVIDED, Map.copyOf(values));
  }
}
