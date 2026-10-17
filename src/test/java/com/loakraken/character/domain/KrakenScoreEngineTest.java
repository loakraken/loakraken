package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 크라켄 점수 엔진(#539) 고정 테스트: 같은 시각에 캡처한 딜러 6명(g01~g03, g05~g07)의 비교 기준 B 입력에 엔진을 돌려 요소·공격력·프리셋·팔찌 총배율·점수가 원본 함수 값과 끝자리까지(double
 * 그대로) 같은지 본다. g05는 프리셋 없음(균등 분배 × 0.7) 사례를 만든 합성 입력이다. 픽스처에는 식별 정보가 없다.
 */
class KrakenScoreEngineTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final KrakenScoreEngine.Tables TABLES = tables();

  private static KrakenScoreEngine.Tables tables() {
    try {
      var doc =
          MAPPER.readTree(
              new ClassPathResource("kraken-score/reference-tables.json")
                  .getContentAsString(StandardCharsets.UTF_8));
      return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JsonNode fixture(String id) throws IOException {
    return MAPPER.readTree(
        new ClassPathResource("kraken-score/" + id + ".json").getContentAsString(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(strings = {"g01", "g02", "g03", "g05", "g06", "g07"})
  void 딜러_요소와_공격력과_프리셋이_원본_값과_끝자리까지_같다(String id) throws IOException {
    var doc = fixture(id);
    assertThat(doc.path("role").asString("")).isEqualTo("DEALER");
    var input = doc.path("input");
    var expected = doc.path("expected");

    var attack = KrakenScoreEngine.attack(input, TABLES);
    var a = expected.path("attack");
    assertThat(attack.base()).as("기본 공격력").isEqualTo(a.path("기본_공격력").asDouble());
    assertThat(attack.attack()).as("공격력").isEqualTo(a.path("공격력").asDouble());
    assertThat(attack.baseCombat()).as("기본 공격력(전투력)").isEqualTo(a.path("기본_공격력_전투력").asDouble());
    assertThat(attack.attackCombat()).as("공격력(전투력)").isEqualTo(a.path("공격력_전투력").asDouble());
    assertThat(attack.attackNoBracelet()).as("공격력(팔찌 제외)").isEqualTo(a.path("공격력_팔찌제외").asDouble());

    var f = KrakenScoreEngine.factors(input, TABLES);
    Map<String, Double> got = new LinkedHashMap<>();
    got.put("공격력", f.attack());
    got.put("진피", f.evolution());
    got.put("깨달음", f.enlightenment());
    got.put("도약", f.leap());
    got.put("추피", f.additionalDamage());
    got.put("적주피", f.enemyDamage());
    got.put("보석딜증", f.gemDamage());
    got.put("보석쿨감", f.gemCooldown());
    got.put("스탯", f.stats());
    got.put("에스더", f.esther());
    got.put("보주", f.orb());
    var factors = expected.path("factors");
    for (var entry : got.entrySet())
      assertThat(entry.getValue()).as(entry.getKey()).isEqualTo(factors.path(entry.getKey()).asDouble());

    var preset = KrakenScoreEngine.preset(input, TABLES);
    assertThat(preset.tag()).isEqualTo(expected.path("presetTag").asString(""));
    assertThat(preset.fallback()).isEqualTo(expected.path("presetFallback").asBoolean());

    // 팔찌 총배율과 점수(요소 × 팔찌 총배율 × 1.021 ÷ 1930)도 끝자리까지.
    assertThat(KrakenScoreBracelet.total(input, TABLES))
        .as("팔찌 총배율")
        .isEqualTo(expected.path("braceletTotal").asDouble());
    assertThat(KrakenScoreEngine.dealerScore(input, TABLES))
        .as("점수")
        .isEqualTo(expected.path("score").asDouble());
  }

  @Test
  void 프리셋이_없으면_피해_보석을_균등하게_나누고_보석딜증에_0_7을_곱한다() throws IOException {
    // g05: 직업 각인·코어 이름을 표에 없게 바꾼 합성 입력. 피해 보석 4개 → 0.25씩, 보석딜증 (1.28 − 1) × 0.7 + 1 = 1.196.
    var input = fixture("g05").path("input");
    var preset = KrakenScoreEngine.preset(input, TABLES);
    assertThat(preset.tag()).isEqualTo("데이터 없음");
    assertThat(preset.fallback()).isTrue();
    assertThat(preset.value().values()).containsOnly(0.25);
    assertThat(KrakenScoreEngine.factors(input, TABLES).gemDamage()).isEqualTo(1.196);
  }

  @Test
  void 표가_빠지면_만들지_않는다() {
    var node = MAPPER.createObjectNode();
    for (var key :
        List.of("RK", "P_", "s0", "af", "MU", "Um", "j1", "WR", "xV", "Uk", "kC", "o9", "ur", "i_", "HZ", "W"))
      node.putObject(key);
    node.putObject("braceletAddonAttack");
    assertThatThrownBy(() -> new KrakenScoreEngine.Tables("x", node))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("we");
  }
}
