package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterLoadout.EffectGrade;
import com.loakraken.character.domain.CombatPowerPolishValues;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 연마 교체의 새 수치 고르기: 같은 이름의 %·고정값이 둘 다 있는 부위(귀걸이 공격력). */
class CombatPowerPolishValueTest {
  private static final CombatPowerPolishValues VALUES =
      new CombatPowerPolishValues(
          "test",
          Map.of(),
          "고대",
          1680,
          List.of(
              new CombatPowerPolishValues.Value(
                  "공격력", true, List.of("귀걸이"), "+0.40%", "+0.95%", "+1.55%"),
              new CombatPowerPolishValues.Value(
                  "공격력", false, List.of("목걸이", "귀걸이", "반지"), "+80", "+195", "+390")));

  @Test
  void 같은_줄의_등급만_바꾸면_지금_줄_값으로_퍼센트인지_고른다() {
    // 웹 시뮬레이터는 등급만 바꿀 때 옵션에 지금 줄 이름을 그대로 보낸다.
    var percentLine = new CharacterLoadout.PolishEffect("공격력", "+0.40%", EffectGrade.LOW);
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력", "HIGH", percentLine))
        .isEqualTo("+1.55%");
    var flatLine = new CharacterLoadout.PolishEffect("공격력", "+80", EffectGrade.LOW);
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력", "HIGH", flatLine))
        .isEqualTo("+390");
  }

  @Test
  void 옵션을_바꿀_때는_끝의_퍼센트_표시로_고른다() {
    var other = new CharacterLoadout.PolishEffect("무기 공격력", "+195", EffectGrade.LOW);
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력 %", "MID", other))
        .isEqualTo("+0.95%");
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력", "MID", other))
        .isEqualTo("+195");
    // 부위에 한 줄뿐이면 표시와 관계없이 그 줄이다.
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "목걸이", "공격력", "LOW", other))
        .isEqualTo("+80");
  }

  @Test
  void 수치표와_같은_장신구는_거래_제한_아이템_레벨이나_연마_값으로_가린다() {
    // 코드 리뷰 10-25: 1540 귀걸이 공격력 % 중 +0.66%가 1680 표의 +0.95%로 읽혔다. 공식 장신구 툴팁에는 '아이템 레벨' 줄이 없고 거래
    // 가능한 장신구에만 '거래 제한 아이템 레벨 N' 줄이 있다(계산 검증 원문 확인).
    var mid = new CharacterLoadout.PolishEffect("공격력", "+0.95%", EffectGrade.MID);
    var old = new CharacterLoadout.PolishEffect("공격력", "+0.66%", EffectGrade.MID);
    assertThat(fits(earring("고대", List.of("거래 제한 아이템 레벨 1680"), mid))).isTrue();
    assertThat(fits(earring("고대", List.of("거래 제한 아이템 레벨 1,540"), mid))).isFalse();
    // 거래 불가(줄 없음): 지금 연마 값이 1680 표의 그 부위 값이면 같은 장신구.
    assertThat(fits(earring("고대", List.of(), mid))).isTrue();
    assertThat(fits(earring("고대", List.of(), old))).isFalse();
    assertThat(fits(earring("유물", List.of("거래 제한 아이템 레벨 1680"), mid))).isFalse();
    assertThat(fits(earring("고대", List.of()))).isFalse();
  }

  @Test
  void 실제_원문의_1680_고대_장신구는_수치표와_같은_장신구다() throws Exception {
    // 기준 캐릭터 원문(고정 자료 l01): 목걸이·귀걸이·반지 모두 1680 고대.
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    var values =
        mapper.readValue(
            new org.springframework.core.io.ClassPathResource("combat-power/polish-values.json")
                .getInputStream(),
            CombatPowerPolishValues.class);
    var raw =
        mapper
            .readTree(
                new org.springframework.core.io.ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .path("raw");
    var accessories =
        com.loakraken.character.infrastructure.ArmoryRawFixtures.loadout(raw).equipment().stream()
            .filter(item -> List.of("목걸이", "귀걸이", "반지").contains(item.type()))
            .toList();
    assertThat(accessories).hasSize(5);
    assertThat(accessories)
        .allMatch(item -> CombatPowerSimulationService.polishTableFits(values, item));
  }

  private static boolean fits(CharacterLoadout.Equipment item) {
    return CombatPowerSimulationService.polishTableFits(VALUES, item);
  }

  private static CharacterLoadout.Equipment earring(
      String grade, List<String> tradeLines, CharacterLoadout.PolishEffect... polish) {
    return new CharacterLoadout.Equipment(
        "귀걸이",
        "귀걸이",
        null,
        grade,
        new CharacterLoadout.EquipmentDetail(
            null,
            null,
            null,
            4,
            null,
            List.of(),
            List.of(),
            List.of(polish),
            List.of(),
            null,
            tradeLines,
            null,
            List.of(),
            null));
  }
}
