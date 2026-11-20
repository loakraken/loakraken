package com.loakraken.character.domain;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 코드 리뷰 #1031 P2: 입력이 빠지면 일부만 계산한 값이 아니라 null, 표가 틀리면 조용한 ×1이 아니라 읽을 때 실패. */
class DealerCombatPowerGuardTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
   * PM 총괄 11-21(#1036 후속): 원문에 줄이 없어 null인 카르마·돌 보너스는 ×1로 계산하고, 줄이 있는데 읽지 못하면 매퍼가 unparsed에 넣어 전투력이
   * null이다. 기준 딜러 원문(l01)을 글자만 바꿔 원문 → 매퍼 → 전투력으로 본다.
   */
  @Test
  void 줄이_없으면_계산하고_있는데_읽지_못하면_null이다() throws Exception {
    var engine = DealerCombatPowerPinTest.engineTables();
    var tables = DealerCombatPowerPinTest.cpTables();
    var text = new ClassPathResource("kraken-score/l01.json").getContentAsString(UTF_8);
    var built = DealerCombatPower.of(input(text), engine, tables);
    assertThat(built).isNotNull();
    assertThat(input(text).has("unparsed")).isFalse();
    // 깨달음 카르마: 설명이 비면(카르마를 열지 않음) 줄이 없는 것 → 계산(카르마 ×1이라 값만 다름).
    var noKarma = once(text, "\"Description\":\"6랭크 29레벨\"", "\"Description\":\"\"");
    var noKarmaInput = input(noKarma);
    assertThat(noKarmaInput.at("/arkPassive/enlightenment/karmalevel").isNull()).isTrue();
    assertThat(noKarmaInput.has("unparsed")).isFalse();
    assertThat(DealerCombatPower.of(noKarmaInput, engine, tables)).isNotNull();
    // 설명이 있는데 'N랭크 N레벨'이 아니면 읽지 못한 것 → null(×1로 낮추지 않음).
    var badKarma = input(once(text, "\"Description\":\"6랭크 29레벨\"", "\"Description\":\"6랭크 29\""));
    assertThat(badKarma.at("/arkPassive/enlightenment/karmalevel").isNull()).isTrue();
    assertThat(badKarma.path("unparsed").toString()).contains("arkPassive.enlightenment.karma");
    assertThat(DealerCombatPower.of(badKarma, engine, tables)).isNull();
    // 돌 레벨 보너스 줄이 있는데 수치를 읽지 못하면 null.
    var badStone = input(once(text, "기본 공격력 +1.50%", "기본 공격력 +?%"));
    assertThat(badStone.at("/armory/abilityStone/attackbonus").isNull()).isTrue();
    assertThat(badStone.path("unparsed").toString()).contains("abilityStone.attackbonus");
    assertThat(DealerCombatPower.of(badStone, engine, tables)).isNull();
  }

  private static JsonNode input(String fileText) throws Exception {
    var raw = (ObjectNode) MAPPER.readTree(fileText).path("raw");
    // 이 표본 원문에는 전투 레벨이 없다(장비로 지은 전투력에 필요).
    ((ObjectNode) raw.path("ArmoryProfile")).put("CharacterLevel", 70);
    return KrakenScoreInputMapper.from(raw);
  }

  /** 꼭 한 번 나오는 글자를 바꾼다(표본이 바뀌면 여기서 실패). */
  private static String once(String text, String from, String to) {
    int first = text.indexOf(from);
    assertThat(first).as(from).isNotNegative();
    assertThat(text.indexOf(from, first + 1)).as(from + " 한 번").isNegative();
    return text.replace(from, to);
  }

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

  /** 코드 리뷰 #1036 P2-2: 있는 칸만 보는 확인으로는 빠진 칸을 못 잡는다. 표 전체의 정규 해시를 리소스에 두고 읽을 때 맞춘다. */
  @Test
  void 표_파일의_해시가_내용과_같고_칸_하나가_빠지면_읽을_때_실패한다() throws Exception {
    var document = read("kraken-score/combat-power-tables.json");
    assertThat(DealerCombatPower.Tables.hash(document.path("tables")))
        .isEqualTo(document.path("sha256").asString());
    assertThatCode(() -> DealerCombatPower.Tables.load(document)).doesNotThrowAnyException();
    // 각인 표 한 칸을 빼면(모양 확인은 통과) 해시가 달라 실패한다.
    var dropped = (ObjectNode) document.deepCopy();
    var engraving = (ObjectNode) dropped.path("tables").path("ability_attack");
    var row = (ObjectNode) engraving.properties().iterator().next().getValue();
    row.remove(row.properties().iterator().next().getKey());
    assertThatCode(() -> new DealerCombatPower.Tables(dropped.path("tables")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> DealerCombatPower.Tables.load(dropped))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("해시");
  }

  /** 코드 리뷰 #1036 P2-1: 완갑 기본 공격력·카르마가 빠지면 낮은 값이 아니라 null. */
  @Test
  void 완갑_코어_보석_효과_입력이_빠져도_null이다() throws Exception {
    var engine = DealerCombatPowerPinTest.engineTables();
    var tables = DealerCombatPowerPinTest.cpTables();
    var input = pin();
    assertThat(DealerCombatPower.of(input, engine, tables)).isNotNull();
    var noVambraceFlat = input.deepCopy();
    var vambrace = noVambraceFlat.path("armory").path("equipment").path("vambrace");
    assertThat(vambrace.isObject()).as("p02에 완갑이 있다").isTrue();
    ((ObjectNode) vambrace).putNull("baseAtkFlat");
    assertThat(DealerCombatPower.of(noVambraceFlat, engine, tables)).isNull();
    var noCorePoint = input.deepCopy();
    var core = noCorePoint.path("arkGrid").path("core");
    assertThat(core.isEmpty()).as("p02에 코어가 있다").isFalse();
    ((ObjectNode) core.get(0)).putNull("point");
    assertThat(DealerCombatPower.of(noCorePoint, engine, tables)).isNull();
    var noWeaponGrade = input.deepCopy();
    ((ObjectNode) noWeaponGrade.path("armory").path("equipment").path("weapon")).putNull("grade");
    assertThat(DealerCombatPower.of(noWeaponGrade, engine, tables)).isNull();
  }

  /**
   * 카르마를 열지 않은 캐릭터·보너스 줄이 없는 돌은 실제로 null이고 비교 기준 식도 ×1이다(핀 p01·p03·p04·p06·p24가 그 값으로 기준과 같다). 그래서
   * null이어도 계산한다.
   */
  @Test
  void 카르마와_돌_보너스가_없는_딜러는_계산한다() throws Exception {
    var engine = DealerCombatPowerPinTest.engineTables();
    var tables = DealerCombatPowerPinTest.cpTables();
    var p01 = read("kraken-score/cp-pins/p01.json");
    var input = p01.path("input");
    assertThat(
            input.path("arkPassive").path("evolution").path("karmaRank").isNull()
                || input.path("arkPassive").path("evolution").path("karmaRank").isMissingNode())
        .isTrue();
    assertThat(DealerCombatPower.of(input, engine, tables))
        .isEqualByComparingTo(p01.path("expected").decimalValue());
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
