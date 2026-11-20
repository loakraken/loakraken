package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 딜러 전투력 요소 가장자리(계산 검증 11-21 'PR2 formula'): 핀 표본에 드문 경우. */
class DealerCombatPowerFactorTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void 카드는_문자열_전체를_먼저_보고_없으면_조각마다_모르는_조각은_곱하지_않는다() throws Exception {
    var tables = DealerCombatPowerPinTest.cpTables();
    assertThat(DealerCombatPower.card(input("{\"card\":null}"), tables)).isEqualTo(1);
    // 원소 6세트 30각 = 1500(공식 문구 누적 15%).
    assertThat(DealerCombatPower.card(input("{\"card\":\"세상을 구하는 빛 30각\"}"), tables))
        .isCloseTo(1.15, org.assertj.core.api.Assertions.within(1e-12));
    // 모르는 조각은 ×1, 아는 조각만 곱한다.
    assertThat(DealerCombatPower.card(input("{\"card\":\"없는 세트 9각 + 세상을 구하는 빛 30각\"}"), tables))
        .isCloseTo(1.15, org.assertj.core.api.Assertions.within(1e-12));
  }

  @Test
  void 에스더_무기는_재련과_엘라_단계의_정확한_키만이고_없으면_곱하지_않는다() throws Exception {
    var tables = DealerCombatPowerPinTest.cpTables();
    assertThat(DealerCombatPower.esther(weapon("에스더 - 2", 8), tables))
        .isCloseTo(1.019, org.assertj.core.api.Assertions.within(1e-12));
    assertThat(DealerCombatPower.esther(weapon("에스더 - 2", 5), tables)).isEqualTo(1);
    assertThat(DealerCombatPower.esther(weapon("고대", 8), tables)).isEqualTo(1);
  }

  @Test
  void 아크_패시브는_곱을_소수_넷째_자리로_자른다() throws Exception {
    var e =
        input(
            "{\"arkPassive\":{\"evolution\":{\"points\":140},\"enlightenment\":{\"points\":101},\"leap\":{\"points\":70}}}");
    // (75·100/1e4+1)(min(7070,7000)/1e4+1)(1400/1e4+1) = 1.75 × 1.7 × 1.14 = 3.3915
    assertThat(DealerCombatPower.arkPassive(e))
        .isCloseTo(3.3915, org.assertj.core.api.Assertions.within(1e-12));
  }

  @Test
  void 펫_피해와_레벨_없으면_계산하지_않는다() throws Exception {
    var tables = DealerCombatPowerPinTest.cpTables();
    assertThat(DealerCombatPower.pet(input("{\"baseEffect\":{\"petDamage\":1}}"), tables))
        .isEqualTo(1.0077);
    assertThat(DealerCombatPower.pet(input("{}"), tables)).isEqualTo(1);
    assertThat(
            DealerCombatPower.of(
                input("{\"profile\":{},\"stats\":{}}"),
                DealerCombatPowerPinTest.engineTables(),
                tables))
        .isNull();
    // 서포터는 딜러 식을 쓰지 않는다(null).
    assertThat(
            DealerCombatPower.of(
                input("{\"profile\":{\"level\":70,\"supportCheck\":true},\"stats\":{}}"),
                DealerCombatPowerPinTest.engineTables(),
                tables))
        .isNull();
  }

  private static tools.jackson.databind.JsonNode weapon(String grade, int reforge) {
    return input(
        "{\"armory\":{\"equipment\":{\"weapon\":{\"grade\":\""
            + grade
            + "\",\"reforge\":"
            + reforge
            + "}}}}");
  }

  private static tools.jackson.databind.JsonNode input(String json) {
    return MAPPER.readTree(json);
  }
}
