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

  /** 범위 밖 값은 %가 null이고, 범위표를 고치도록 부위·단계·값을 한 번만 WARN으로 남긴다(캐릭터 이름 없음). */
  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void 범위_밖_값은_백분율_null이고_한_번만_남긴다(org.springframework.boot.test.system.CapturedOutput output) {
    var detail =
        new CharacterLoadout.EquipmentDetail(
            null,
            90,
            null,
            4,
            null,
            java.util.List.of(
                new CharacterLoadout.EffectGroup("기본 효과", java.util.List.of("힘 +19999"))),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null);
    for (int i = 0; i < 2; i++) {
      var stats =
          CharacterLoadoutResponse.EquipmentResponse.from(
                  new CharacterLoadout.Equipment("목걸이", "시험 목걸이", null, "고대", detail))
              .detail()
              .accessoryStats();
      assertThat(stats.mainStatRangePct()).isNull();
      assertThat(stats.mainStat()).isEqualTo(19999);
    }
    var line = "event=accessory_stat_out_of_range slot=목걸이 stage=0 mainStat=19999";
    assertThat(output.getOut().split(java.util.regex.Pattern.quote(line), -1).length - 1)
        .isEqualTo(1);
  }

  @Test
  void 장신구가_아니면_싣지_않는다() throws Exception {
    var response =
        CharacterLoadoutResponse.EquipmentResponse.from(
            new CharacterLoadout.Equipment("팔찌", "시험 팔찌", null, "유물", realNecklace()));
    assertThat(response.detail().accessoryStats()).isNull();
  }
}
