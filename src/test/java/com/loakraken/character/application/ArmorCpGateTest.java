package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.DealerCombatPower;
import com.loakraken.character.domain.KrakenScoreEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 방어구 전투력 문(코드 리뷰 #1036): |r| ≤ 0.10% 경계, 스위치 꺼짐, 입력 없음, 지표. */
class ArmorCpGateTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void r이_정확히_0점1퍼센트면_열고_넘으면_닫는다() throws Exception {
    var registry = new SimpleMeterRegistry();
    var gate = new ArmorCpGate(true, engine(), tables(), registry);
    var input = pin();
    var built = DealerCombatPower.of(input, engine(), tables());
    assertThat(built).isNotNull();
    assertThat(gate.state(snapshot("문경계안", built.multiply(new BigDecimal("1.0010")), input)))
        .isEqualTo(ArmorCpGate.State.OPEN);
    assertThat(gate.state(snapshot("문경계밖", built.multiply(new BigDecimal("1.0011")), input)))
        .isEqualTo(ArmorCpGate.State.RESIDUAL_HIGH);
    // 공식이 낮은 쪽도 같은 |r|.
    assertThat(gate.state(snapshot("문경계아래", built.multiply(new BigDecimal("0.9989")), input)))
        .isEqualTo(ArmorCpGate.State.RESIDUAL_HIGH);
    assertThat(
            registry
                .get("loakraken.combat_power.residual")
                .tag("outcome", "open")
                .summary()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("loakraken.combat_power.residual")
                .tag("outcome", "residual_high")
                .summary()
                .count())
        .isEqualTo(2);
    // 같은 스냅샷은 한 번만 잰다.
    gate.state(snapshot("문경계안", built.multiply(new BigDecimal("1.0010")), input));
    assertThat(
            registry
                .get("loakraken.combat_power.residual")
                .tag("outcome", "open")
                .summary()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 스위치가_꺼지면_재지_않고_지표도_없으며_입력이_없으면_열지_않는다() throws Exception {
    var registry = new SimpleMeterRegistry();
    var off = new ArmorCpGate(false, engine(), tables(), registry);
    assertThat(off.state(snapshot("문꺼짐", BigDecimal.TEN, pin())))
        .isEqualTo(ArmorCpGate.State.DISABLED);
    assertThat(registry.find("loakraken.combat_power.residual").meters()).isEmpty();
    var on = new ArmorCpGate(true, engine(), tables(), new SimpleMeterRegistry());
    assertThat(on.state(snapshot("문입력없음", BigDecimal.TEN, null)))
        .isEqualTo(ArmorCpGate.State.UNAVAILABLE);
  }

  @Test
  void 같은_스냅샷을_동시에_물어도_한_번만_잰다() throws Exception {
    var registry = new SimpleMeterRegistry();
    var gate = new ArmorCpGate(true, engine(), tables(), registry);
    var input = pin();
    var built = DealerCombatPower.of(input, engine(), tables());
    var snapshot = snapshot("문동시", built, input);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      var calls = new java.util.ArrayList<java.util.concurrent.Future<ArmorCpGate.State>>();
      for (int i = 0; i < 16; i++)
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  return gate.state(snapshot);
                }));
      start.countDown();
      for (var call : calls) assertThat(call.get()).isEqualTo(ArmorCpGate.State.OPEN);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            registry
                .get("loakraken.combat_power.residual")
                .tag("outcome", "open")
                .summary()
                .count())
        .isEqualTo(1);
  }

  private static CharacterResult snapshot(String name, BigDecimal combatPower, JsonNode input) {
    var profile =
        new CharacterProfile(
            name,
            "문서버",
            "블레이드",
            70,
            new BigDecimal("1770"),
            combatPower,
            null,
            null,
            null,
            null,
            List.of());
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null, List.of());
    return new CharacterResult(
        CharacterTestFixture.details(profile, loadout, List.of()),
        Instant.parse("2026-11-21T00:00:00Z"),
        null,
        input);
  }

  private static JsonNode pin() throws Exception {
    return read("kraken-score/cp-pins/p02.json").path("input");
  }

  private static KrakenScoreEngine.Tables engine() throws Exception {
    var doc = read("kraken-score/reference-tables.json");
    return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
  }

  private static DealerCombatPower.Tables tables() throws Exception {
    return new DealerCombatPower.Tables(
        read("kraken-score/combat-power-tables.json").path("tables"));
  }

  private static JsonNode read(String path) throws Exception {
    return MAPPER.readTree(new ClassPathResource(path).getInputStream());
  }
}
