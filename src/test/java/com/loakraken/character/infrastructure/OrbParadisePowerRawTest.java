package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.EquipmentSummary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 실제 공식 원문(크라켄 점수 표본)의 보주 Tooltip에서 실제 수집 경로로 '달성 최대 낙원력'을 읽는다(픽스처만이 아니라 실제 원문에 있는지). */
class OrbParadisePowerRawTest {
  @Test
  void 실제_원문의_보주에서_달성_최대_낙원력을_읽는다() throws Exception {
    try (var resource = getClass().getResourceAsStream("/kraken-score/l01.json")) {
      var raw = new JsonMapper().readTree(resource).path("raw");
      var loadout = ArmoryRawFixtures.loadout(raw);
      var orb =
          loadout.equipment().stream()
              .filter(item -> item != null && "보주".equals(item.type()))
              .findFirst()
              .orElseThrow();
      assertThat(EquipmentSummary.paradisePower(orb.detail())).isEqualTo(35647915L);
    }
  }
}
