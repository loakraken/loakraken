package com.loakraken.character.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.infrastructure.LostArkEquipmentTooltipAccess;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 캐릭터 전투정보 응답의 장비 상세에 장신구 기본 스탯 범위(흡수 #6)를 싣는다. 장신구가 아니면 null. */
class CharacterLoadoutAccessoryStatsTest {
  private static CharacterLoadout.EquipmentDetail realNecklace() throws Exception {
    try (var in =
        CharacterLoadoutAccessoryStatsTest.class.getResourceAsStream(
            "/fixtures/accessory-tooltip-relic-necklace.json")) {
      return LostArkEquipmentTooltipAccess.parse(
          "엄숙한 결의의 목걸이", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void 장신구_장비_상세에_연마_단계와_힘민지_범위_백분율을_싣는다() throws Exception {
    var response =
        CharacterLoadoutResponse.EquipmentResponse.from(
            new CharacterLoadout.Equipment("목걸이", "엄숙한 결의의 목걸이", null, "유물", realNecklace()));
    var stats = response.detail().accessoryStats();
    assertThat(stats)
        .isEqualTo(
            new CharacterLoadoutResponse.AccessoryStatsResponse(
                3, 15366, 15178, 17857, new BigDecimal("7.02")));
  }

  @Test
  void 장신구가_아니면_싣지_않는다() throws Exception {
    var response =
        CharacterLoadoutResponse.EquipmentResponse.from(
            new CharacterLoadout.Equipment("팔찌", "시험 팔찌", null, "유물", realNecklace()));
    assertThat(response.detail().accessoryStats()).isNull();
  }
}
