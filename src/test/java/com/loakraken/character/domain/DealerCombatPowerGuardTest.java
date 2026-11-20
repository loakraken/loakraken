package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 코드 리뷰 #1031 P2: 입력이 빠지면 일부만 계산한 값이 아니라 null, 표가 틀리면 조용한 ×1이 아니라 읽을 때 실패.
 */
class DealerCombatPowerGuardTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void 입력이_빠지면_낮은_값이_아니라_null이다() throws Exception {
    var engine = DealerCombatPowerPinTest.engineTables();
    var tables = DealerCombatPowerPinTest.cpTables();
    var input = pin();
    assertThat(DealerCombatPower.of(input, engine, tables)).isNotNull();
    // 투구 주스탯이 빠지면(null) 0으로 들어가 낮은 전투력이 나오던 자리.
    var noHelmet = input.deepCopy();
    ((ObjectNode) noHelmet.path("armory").path("equipment").path("helmet")).putNull("stat");
    assertThat(DealerCombatPower.of(noHelmet, engine, tables)).isNull();
    // 스탯 객체가 없으면 던지지 않고 null.
    var noStats = input.deepCopy();
    ((ObjectNode) noStats).remove("stats");
    assertThatCode(() -> DealerCombatPower.of(noStats, engine, tables)).doesNotThrowAnyException();
    assertThat(DealerCombatPower.of(noStats, engine, tables)).isNull();
    // 아크 패시브 포인트가 없으면 null(0이면 ×1로 낮아짐).
    var noLeap = input.deepCopy();
    ((ObjectNode) noLeap.path("arkPassive").path("leap")).putNull("points");
    assertThat(DealerCombatPower.of(noLeap, engine, tables)).isNull();
  }

  @Test
  void 표가_틀리면_읽을_때_실패한다() throws Exception {
    var raw = read("kraken-score/combat-power-tables.json").path("tables");
    // 레벨 표가 null이면 레벨 70이 ×1(약 −22.75%)이 되던 자리.
    var nullLevel = raw.deepCopy();
    ((ObjectNode) nullLevel).putNull("level");
    assertThatThrownBy(() -> new DealerCombatPower.Tables(nullLevel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("level");
    var badCell = raw.deepCopy();
    ((ObjectNode) badCell.path("level")).put("70", "x");
    assertThatThrownBy(() -> new DealerCombatPower.Tables(badCell))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("level.70");
    var badOrb = raw.deepCopy();
    var firstOrb = badOrb.path("orb").properties().iterator().next().getKey();
    ((ObjectNode) badOrb.path("orb")).putArray(firstOrb).add(0.2);
    assertThatThrownBy(() -> new DealerCombatPower.Tables(badOrb))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orb");
  }

  @Test
  void 표는_밖에서_바꿀_수_없다() throws Exception {
    var engine = DealerCombatPowerPinTest.engineTables();
    var tables = DealerCombatPowerPinTest.cpTables();
    var input = pin();
    var before = DealerCombatPower.of(input, engine, tables);
    ((ObjectNode) tables.tables()).remove("level");
    assertThat(DealerCombatPower.of(input, engine, tables)).isEqualByComparingTo(before);
  }

  private static JsonNode pin() throws Exception {
    return read("kraken-score/cp-pins/p02.json").path("input");
  }

  private static JsonNode read(String path) throws Exception {
    return MAPPER.readTree(new ClassPathResource(path).getInputStream());
  }
}
