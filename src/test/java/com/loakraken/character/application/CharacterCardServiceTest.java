package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CharacterCardServiceTest {
  @Test
  void 보석_평균_레벨은_4티어_보석만_센다() {
    var gems =
        new CharacterLoadout.Gems(
            Arrays.asList(
                gem("10레벨 겁화의 보석", 10),
                gem("9레벨 작열의 보석", 9),
                gem("8레벨 광휘의 보석", 8),
                gem("10레벨 멸화의 보석", 10),
                gem("레벨 없는 겁화의 보석", null)),
            null);
    assertThat(CharacterCardService.tier4GemMeanLevel(gems)).isEqualByComparingTo("9.00");
    assertThat(
            CharacterCardService.tier4GemMeanLevel(
                new CharacterLoadout.Gems(java.util.List.of(gem("10레벨 홍염의 보석", 10)), null)))
        .isNull();
    assertThat(CharacterCardService.tier4GemMeanLevel(null)).isNull();
  }

  private static CharacterLoadout.Gem gem(String name, Integer level) {
    return new CharacterLoadout.Gem(0, name, "icon", level, "고대");
  }
}
