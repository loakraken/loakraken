package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 젬 옵션 실 효율(#520). 기준 값은 계산 검증이 비교 기준 A 화면과 대조한 캐릭터 한 명의 구성(2026-10-15 관측)이다: 장신구 공격력 0.95 +
 * 0.40, 혼돈의 별 : 공격 유물 17P(1.65), 무기 품질 100(추가 피해 30), 목걸이 추가 피해 2.60, 팔찌 추가 피해 6.00. 젬: 공격력 Lv28(질서
 * 20·혼돈 8), 추가 피해 Lv51(질서 28·혼돈 23), 보스 피해 Lv4(혼돈 4).
 */
class ArkGridRealEfficiencyTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final ArkGridRealEfficiency.Table TABLE = table();

  private static ArkGridRealEfficiency.Table table() {
    try (InputStream in =
        new ClassPathResource("combat-power/ark-grid-real-table.json").getInputStream()) {
      return MAPPER.readValue(in, ArkGridRealEfficiency.Table.class);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static CharacterLoadout.GridGem gem(Object... options) {
    var list = new ArrayList<CharacterLoadout.GemOption>();
    for (int i = 0; i < options.length; i += 2)
      list.add(new CharacterLoadout.GemOption((String) options[i], (Integer) options[i + 1], null, null));
    return new CharacterLoadout.GridGem(
        0,
        null,
        true,
        "고대",
        new CharacterLoadout.GemDetail(null, null, null, null, null, null, null, list),
        null);
  }

  private static CharacterLoadout.GridSlot slot(
      String name, int point, String grade, CharacterLoadout.GridGem... gems) {
    return new CharacterLoadout.GridSlot(0, null, name, point, grade, List.of(gems));
  }

  private static CharacterLoadout.Equipment equipment(
      String type,
      Integer quality,
      List<CharacterLoadout.PolishEffect> polish,
      List<CharacterLoadout.BraceletEffect> bracelet) {
    return new CharacterLoadout.Equipment(
        type,
        type,
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            null, quality, null, null, null, List.of(), List.of(), polish, bracelet));
  }

  private static CharacterLoadout.PolishEffect polish(String name, String value) {
    return new CharacterLoadout.PolishEffect(name, value, null);
  }

  private static CharacterLoadout loadout(String braceletLine) {
    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(
                slot("질서의 해 코어 : 살상연희", 18, "유물", gem("공격력", 10, "추가 피해", 14)),
                slot("질서의 달 코어 : 쾌도난마", 18, "유물", gem("공격력", 10, "추가 피해", 14)),
                slot(
                    "혼돈의 별 코어 : 공격",
                    17,
                    "유물",
                    gem("공격력", 8, "추가 피해", 13, "보스 피해", 4)),
                slot("혼돈의 해 코어 : 현란한 공격", 17, "유물", gem("추가 피해", 10))),
            List.of(
                new CharacterLoadout.GridEffect("공격력", 28),
                new CharacterLoadout.GridEffect("추가 피해", 51),
                new CharacterLoadout.GridEffect("보스 피해", 4)));
    return new CharacterLoadout(
        List.of(
            equipment("무기", 100, List.of(), List.of()),
            equipment(
                "목걸이",
                90,
                List.of(polish("추가 피해", "+2.60%"), polish("공격력", "+0.95%")),
                List.of()),
            equipment("귀걸이", 90, List.of(polish("공격력", "+0.40%")), List.of()),
            equipment(
                "반지", 90, List.of(polish("무기 공격력", "+1.80%")), List.of()),
            equipment(
                "팔찌",
                null,
                List.of(),
                List.of(new CharacterLoadout.BraceletEffect(braceletLine, null)))),
        null,
        null,
        null,
        null,
        grid);
  }

  @Test
  void 기준_캐릭터의_옵션_줄과_계열별_값을_재현한다() {
    var result = ArkGridRealEfficiency.compute(TABLE, loadout("추가 피해 +6.00%"));
    assertThat(result.reason()).isNull();
    // 공격력 풀 100 + 0.95 + 0.40 + 1.65 + 1.02 = 104.02, 추가 피해 풀 100 + 30 + 2.60 + 6.00 + 4.12 = 142.72
    assertThat(result.attackPool()).isEqualByComparingTo("104.02");
    assertThat(result.addDamagePool()).isEqualByComparingTo("142.72");
    assertThat(result.options())
        .extracting(
            ArkGridRealEfficiency.Option::name,
            ArkGridRealEfficiency.Option::level,
            option -> option.inGamePercent().toPlainString(),
            option -> option.percent().toPlainString())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("공격력", 28, "1.02", "0.99"),
            org.assertj.core.groups.Tuple.tuple("추가 피해", 51, "4.12", "2.97"),
            org.assertj.core.groups.Tuple.tuple("보스 피해", 4, "0.33", "0.33"));
    assertThat(result.optionsPercent()).isEqualByComparingTo("4.33");

    var order = result.series().get(0);
    assertThat(order.series()).isEqualTo("질서");
    assertThat(order.options())
        .extracting(ArkGridRealEfficiency.Option::name, option -> option.percent().toPlainString())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("공격력", "0.70"),
            org.assertj.core.groups.Tuple.tuple("추가 피해", "1.61"));
    // 질서 옵션 = 1.0070 × 1.0161 (포인트 0.32는 다음 단계) → 2.33
    assertThat(order.optionsPercent()).isEqualByComparingTo("2.33");
    var chaos = result.series().get(1);
    assertThat(chaos.series()).isEqualTo("혼돈");
    assertThat(chaos.options())
        .extracting(ArkGridRealEfficiency.Option::name, option -> option.percent().toPlainString())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("공격력", "0.27"),
            org.assertj.core.groups.Tuple.tuple("추가 피해", "1.32"),
            org.assertj.core.groups.Tuple.tuple("보스 피해", "0.33"));
    assertThat(chaos.optionsPercent()).isEqualByComparingTo("1.93");
  }

  @Test
  void 팔찌의_추가_피해가_증가_문장도_추가_피해_풀에_든다() {
    var result =
        ArkGridRealEfficiency.compute(
            TABLE, loadout("공격 적중 시 추가 피해가 6.00% 증가한다."));
    assertThat(result.addDamagePool()).isEqualByComparingTo("142.72");
    // 팔찌가 없으면 풀이 작아져 값이 달라진다(3.10).
    var none = ArkGridRealEfficiency.compute(TABLE, loadout("치명타 적중률 +4.20%"));
    assertThat(none.options().get(1).percent()).isEqualByComparingTo("3.10");
  }

  @Test
  void 젬_레벨_합이_효과_레벨과_다르면_계산하지_않는다() {
    var base = loadout("추가 피해 +6.00%");
    var grid =
        new CharacterLoadout.ArkGrid(
            base.arkGrid().slots(),
            List.of(
                new CharacterLoadout.GridEffect("공격력", 29),
                new CharacterLoadout.GridEffect("추가 피해", 51),
                new CharacterLoadout.GridEffect("보스 피해", 4)));
    var mismatched =
        new CharacterLoadout(base.equipment(), null, null, null, null, grid);
    var result = ArkGridRealEfficiency.compute(TABLE, mismatched);
    assertThat(result.reason()).contains("맞지 않아");
    assertThat(result.optionsPercent()).isNull();
  }

  @Test
  void 레벨_식은_옵션별_계수로_내림한다() {
    assertThat(TABLE.percent("공격력", 8)).isEqualByComparingTo("0.29");
    assertThat(TABLE.percent("공격력", 20)).isEqualByComparingTo("0.73");
    assertThat(TABLE.percent("추가 피해", 23)).isEqualByComparingTo("1.85");
    assertThat(TABLE.percent("낙인력", 32)).isEqualByComparingTo("5.33");
    assertThat(TABLE.percent("아군 피해 강화", 25)).isEqualByComparingTo("1.31");
    assertThat(TABLE.percent("공격력", 0)).isEqualByComparingTo("0");
    assertThat(TABLE.percent("없는 옵션", 3)).isNull();
  }
}
