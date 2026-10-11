package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout.EffectGrade;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재련 단계 모델(#319): 계산 검증 표본(loalab-refine-model-2026-10-11.json)의 세 캐릭터를 공식 응답과 같은 형식의 입력으로 다시 만들어,
 * 기대 전투력을 ±0.01로 맞춘다.
 */
class CombatPowerRefineModelTest {
  private static CombatPowerRefineModel.Coefficients coefficients;

  @BeforeAll
  static void 계수() throws IOException {
    try (var input =
        new ClassPathResource("combat-power/refine-coefficients.json").getInputStream()) {
      coefficients = new JsonMapper().readValue(input, CombatPowerRefineModel.Coefficients.class);
    }
  }

  @Test
  void 언새늘_전율_무기와_투구_단계() {
    // B 188,163, q 7.45%, F 2,030, W 고정 222,947(무기 208,130 + 팔 보호구 14,817), 무공% 1.80 + 0.80 + 카르마
    // 29 × 0.1.
    var loadout =
        loadout(19, 19, "운명의 전율 검", "208130", "2030", "14817", List.of("+1.80%", "+0.80%"), 29);
    var model =
        CombatPowerRefineModel.of(coefficients, loadout, stats("188163"), new BigDecimal("7.45"));
    assertThat(model.unsupportedReason()).isNull();
    assertThat(after("3345.24", model, Map.of(1, 20))).isEqualByComparingTo("3352.32");
    assertThat(after("3345.24", model, Map.of(0, 20))).isEqualByComparingTo("3383.60");
    assertThat(after("3345.24", model, Map.of(0, 25)).subtract(new BigDecimal("3583.14")).abs())
        .isLessThanOrEqualTo(new BigDecimal("0.01"));
    assertThat(after("3345.24", model, Map.of(0, 16))).isEqualByComparingTo("3233.15");
    // 팔찌 "민첩 +15360"(S 항, 계산 검증 #332 회신): 목걸이 같은 수치와 같은 ×1.010768.
    assertThat(
            model
                .ratio(Map.of(), BigDecimal.ZERO, new BigDecimal("15360"))
                .setScale(6, RoundingMode.HALF_UP))
        .isEqualByComparingTo("1.010768");
  }

  @Test
  void 에스더_무기는_계산하지_않고_전율_방어구는_계산한다() {
    // inzai: B 267,396, q 17.7%, F 9,050, W 고정 283,652(에스더 259,554 + 22,940 + 장신구 960 + 195 + 3),
    // 무공% 3 + 3 + 3.0.
    var inzai =
        loadout(
            10,
            25,
            "에스더 테스트 검",
            "259554",
            "9050",
            "22940",
            List.of("+960", "+195", "+3", "+3.00%", "+3.00%"),
            30);
    var model =
        CombatPowerRefineModel.of(coefficients, inzai, stats("267396"), new BigDecimal("17.7"));
    assertThat(after("11028.4", model, Map.of(1, 24))).isEqualByComparingTo("11007.13");
    assertThat(after("11028.4", model, Map.of(1, 19))).isEqualByComparingTo("10907.74");
    assertThat(model.cannotSet(0, 11)).contains("전율 장비만");
    // 만배: W 고정 285,374(장신구 960 × 3).
    var manbae =
        loadout(
            10,
            25,
            "에스더 테스트 검",
            "259554",
            "9050",
            "22940",
            List.of("+960", "+960", "+960", "+3.00%", "+3.00%"),
            30);
    var other =
        CombatPowerRefineModel.of(coefficients, manbae, stats("267757"), new BigDecimal("17.7"));
    assertThat(after("10724.16", other, Map.of(1, 24))).isEqualByComparingTo("10703.41");
  }

  @Test
  void 입력을_읽지_못하면_계산하지_않는다() {
    var loadout = loadout(19, 19, "운명의 전율 검", "208130", "2030", "14817", List.of(), 29);
    assertThat(
            CombatPowerRefineModel.of(coefficients, loadout, List.of(), new BigDecimal("7.45"))
                .unsupportedReason())
        .contains("기본 공격력");
    assertThat(
            CombatPowerRefineModel.of(coefficients, loadout, stats("188163"), null)
                .unsupportedReason())
        .contains("기본 공격력 % 합");
    var model =
        CombatPowerRefineModel.of(coefficients, loadout, stats("188163"), new BigDecimal("7.45"));
    assertThat(model.cannotSet(1, 26)).contains("11~25단계");
  }

  private static BigDecimal after(
      String combatPower, CombatPowerRefineModel.Model model, Map<Integer, Integer> levels) {
    return new BigDecimal(combatPower)
        .multiply(model.ratio(levels))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static List<CharacterProfile.Stat> stats(String baseAttack) {
    return List.of(
        new CharacterProfile.Stat(
            "공격력",
            "0",
            List.of(
                "적에게 주는 피해를 계산할 때 기준이 되는 값입니다.",
                "힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 " + baseAttack + " 입니다.")));
  }

  /** 장비 순번: 0 무기, 1 투구, 2 상의, 3 하의, 4 장갑, 5 어깨, 6 완갑, 7~ 장신구(연마 무기 공격력). */
  private static CharacterLoadout loadout(
      int weaponLevel,
      int armorLevel,
      String weaponName,
      String weaponAttack,
      String armGuardBase,
      String armGuardWeapon,
      List<String> polishWeaponAttack,
      int karma) {
    var equipment = new ArrayList<CharacterLoadout.Equipment>();
    equipment.add(
        item(
            "무기",
            "+" + weaponLevel + " " + weaponName,
            weaponLevel,
            List.of("무기 공격력 +" + weaponAttack)));
    for (var part : List.of("투구", "상의", "하의", "장갑", "어깨"))
      equipment.add(
          item(part, "+" + armorLevel + " 운명의 전율 " + part, armorLevel, List.of("힘 +100")));
    equipment.add(
        item(
            "완갑",
            "+13 운명의 전율 완갑",
            null,
            List.of("무기 공격력 +" + armGuardWeapon, "기본 공격력 +" + armGuardBase)));
    for (var value : polishWeaponAttack)
      equipment.add(
          new CharacterLoadout.Equipment(
              "귀걸이",
              "테스트 귀걸이",
              null,
              "고대",
              new CharacterLoadout.EquipmentDetail(
                  null,
                  80,
                  null,
                  4,
                  null,
                  List.of(),
                  List.of(),
                  List.of(new CharacterLoadout.PolishEffect("무기 공격력", value, EffectGrade.MID)),
                  List.of())));
    var avatars = new ArrayList<CharacterLoadout.Avatar>();
    for (var type : List.of("무기 아바타", "머리 아바타", "상의 아바타", "하의 아바타")) {
      avatars.add(
          new CharacterLoadout.Avatar(type, "전설 " + type, null, "전설", false, true, List.of()));
      avatars.add(
          new CharacterLoadout.Avatar(type, "영웅 " + type, null, "영웅", false, false, List.of()));
    }
    var arkPassive =
        new CharacterLoadout.ArkPassive(
            "깨달음",
            true,
            List.of(new CharacterLoadout.PassivePoint("깨달음", 101, "6랭크 " + karma + "레벨")),
            List.of());
    return new CharacterLoadout(
        equipment, null, null, null, arkPassive, null, List.of(), avatars, List.of());
  }

  private static CharacterLoadout.Equipment item(
      String type, String name, Integer refine, List<String> base) {
    return new CharacterLoadout.Equipment(
        type,
        name,
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            refine,
            null,
            null,
            4,
            null,
            List.of(new CharacterLoadout.EffectGroup("기본 효과", base)),
            List.of(),
            List.of(),
            List.of()));
  }
}
