package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보석 B단계(#243) 근거 고정: 볼트 「전투력 검증 준비」 7절. 기준 캐릭터(공식 전투력 4,052.77, 4티어 6레벨 11칸, 스톤 레벨 보너스 1.50%, 완갑
 * +13 추가 효과 1.00%)을 비교 기준 시뮬레이터에 넣은 값 두 건을 재현한다. 캐릭터 데이터는 같은 형식의 합성 값이다. 계산 검증 표본(#244, 5명 30건)도
 * 기대값으로 쓴다.
 */
class CombatPowerGemModelTest {
  private static final BigDecimal BEFORE = new BigDecimal("4052.77");
  private static final CombatPowerGemModel.Coefficients COEFFICIENTS = coefficients();
  private static final Map<Integer, String> OPTION = Map.of(6, "0.45", 10, "1.20");

  @Test
  void 교차_검증_표본_5명_30건과_0_01_안에서_같다() throws Exception {
    JsonNode root;
    try (var input =
        new ClassPathResource("verification/combat-power/ref-a-gem-deltas-2026-10-10.json")
            .getInputStream()) {
      root = new JsonMapper().readTree(input);
    }
    int count = 0;
    for (var character : root.path("characters")) {
      int level = character.at("/currentGems/levels/0").asInt();
      var model =
          CombatPowerGemModel.of(
              COEFFICIENTS,
              loadout(
                  level, OPTION.get(level), extraLines(character.at("/armGuard/pct").asText())));
      assertThat(model.unsupportedReason()).isNull();
      var before = new BigDecimal(character.path("combatPower").asText()).setScale(2);
      for (var sample : character.path("cases")) {
        var levels = new HashMap<Integer, Integer>();
        var gemLevels = sample.path("gemLevels");
        for (int slot = 0; slot < gemLevels.size(); slot++)
          levels.put(slot, gemLevels.get(slot).asInt());
        var actual = value(model, levels, before);
        var label = character.path("id").asText() + " " + sample.path("change").asText();
        assertThat(actual)
            .as(label)
            .isEqualByComparingTo(new BigDecimal(sample.path("formula").asText()));
        assertThat(
                actual.subtract(new BigDecimal(sample.path("expectedRef").asText())).abs())
            .as(label)
            .isLessThanOrEqualTo(new BigDecimal("0.01"));
        count++;
      }
    }
    assertThat(count).isEqualTo(30);
  }

  @Test
  void 기준_캐릭터_보석_1칸_6에서_7은_4083_29다() {
    var model = CombatPowerGemModel.of(COEFFICIENTS, loadout(6, "0.45", extraLines()));
    assertThat(model.unsupportedReason()).isNull();
    assertThat(model.otherBaseAttackPct()).isEqualByComparingTo("2.50");
    assertThat(value(model, Map.of(0, 7))).isEqualByComparingTo("4083.29");
  }

  @Test
  void 기준_캐릭터_보석_11칸_6에서_7은_4400_93이다() {
    var model = CombatPowerGemModel.of(COEFFICIENTS, loadout(6, "0.45", extraLines()));
    var all = new HashMap<Integer, Integer>();
    for (int slot = 0; slot < 11; slot++) all.put(slot, 7);
    assertThat(value(model, all)).isEqualByComparingTo("4400.93");
  }

  @Test
  void 바꾸지_않으면_비율은_1이다() {
    var model = CombatPowerGemModel.of(COEFFICIENTS, loadout(10, "1.20", extraLines()));
    assertThat(value(model, Map.of())).isEqualByComparingTo(BEFORE);
    assertThat(value(model, Map.of(3, 10))).isEqualByComparingTo(BEFORE);
  }

  @Test
  void 기본_공격력_수치가_없는_레벨로는_바꾸지_않는다() {
    var model = CombatPowerGemModel.of(COEFFICIENTS, loadout(6, "0.45", extraLines()));
    assertThat(model.cannotSet(0, 5)).isEqualTo("5레벨 보석의 기본 공격력 수치를 아직 확인하지 않았습니다.");
    assertThat(model.cannotSet(0, 8)).isNull();
    assertThat(model.ratio(Map.of(0, 5))).isNull();
  }

  @Test
  void 공식_표기가_계수표와_다르면_계산하지_않는다() {
    var model = CombatPowerGemModel.of(COEFFICIENTS, loadout(6, "0.50", extraLines()));
    assertThat(model.unsupportedReason())
        .isEqualTo("보석 0번의 기본 공격력 표기(0.50%)가 계수표(0.45%)와 달라 계산하지 않습니다.");
    assertThat(model.ratio(Map.of())).isNull();
  }

  @Test
  void 확인하지_않은_기본_공격력_출처가_있으면_계산하지_않는다() {
    var lines = new ArrayList<>(extraLines());
    lines.add(
        equipment(
            "목걸이", "테스트 목걸이", new CharacterLoadout.EffectGroup("연마 효과", List.of("기본 공격력 +0.80%"))));
    var model = CombatPowerGemModel.of(COEFFICIENTS, loadout(6, "0.45", lines));
    assertThat(model.unsupportedReason())
        .isEqualTo("확인하지 않은 기본 공격력 % 출처(「기본 공격력 +0.80%」)가 있어 보석 변화는 계산하지 않습니다.");
  }

  @Test
  void 완갑_기본_효과의_정수_기본_공격력은_출처가_아니다() {
    // "기본 공격력 +2030"(정수)은 % 문구가 아니라 순수 기본 공격력이다.
    var model = CombatPowerGemModel.of(COEFFICIENTS, loadout(6, "0.45", extraLines()));
    assertThat(model.unsupportedReason()).isNull();
  }

  @Test
  void 삼티어_보석은_아직_계산하지_않는다() {
    var gems =
        new CharacterLoadout.Gems(
            List.of(new CharacterLoadout.Gem(0, "7레벨 멸화의 보석", null, 7, "전설")),
            new CharacterLoadout.GemEffects("", List.of()));
    var loadout = new CharacterLoadout(extraLines(), null, gems, null, null, null, List.of());
    assertThat(CombatPowerGemModel.of(COEFFICIENTS, loadout).unsupportedReason())
        .isEqualTo("3티어 보석 계수는 아직 확인하지 않았습니다.");
  }

  private static BigDecimal value(CombatPowerGemModel.Model model, Map<Integer, Integer> levels) {
    return value(model, levels, BEFORE);
  }

  private static BigDecimal value(
      CombatPowerGemModel.Model model, Map<Integer, Integer> levels, BigDecimal before) {
    return before
        .multiply(model.ratio(levels))
        .divide(model.ratio(Map.of()), MathContext.DECIMAL64)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static CharacterLoadout loadout(
      int level, String option, List<CharacterLoadout.Equipment> equipment) {
    var items = new ArrayList<CharacterLoadout.Gem>();
    var skills = new ArrayList<CharacterLoadout.GemSkill>();
    for (int slot = 0; slot < 11; slot++) {
      items.add(new CharacterLoadout.Gem(slot, level + "레벨 광휘의 보석", null, level, "고대"));
      skills.add(
          new CharacterLoadout.GemSkill(
              slot,
              "스킬 " + slot,
              List.of(slot % 2 == 0 ? "피해 28.00% 증가" : "재사용 대기시간 16.00% 감소"),
              "기본 공격력 " + option + "% 증가",
              null));
    }
    return new CharacterLoadout(
        equipment,
        null,
        new CharacterLoadout.Gems(items, new CharacterLoadout.GemEffects("기본 공격력 총합", skills)),
        null,
        null,
        null,
        List.of());
  }

  private static List<CharacterLoadout.Equipment> extraLines() {
    return extraLines("1.00");
  }

  private static List<CharacterLoadout.Equipment> extraLines(String armGuardPct) {
    return List.of(
        equipment(
            "어빌리티 스톤",
            "위대한 비상의 돌",
            new CharacterLoadout.EffectGroup(
                "무작위 각인 효과", List.of("[아드레날린] Lv.3", "[원한] Lv.2", "[레벨 보너스] 기본 공격력 +1.50%"))),
        new CharacterLoadout.Equipment(
            "완갑",
            "+13 운명의 전율 완갑",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                null,
                null,
                4,
                null,
                List.of(
                    new CharacterLoadout.EffectGroup(
                        "기본 효과", List.of("무기 공격력 +14817", "기본 공격력 +2030")),
                    new CharacterLoadout.EffectGroup(
                        "추가 효과", List.of("기본 공격력 +" + armGuardPct + "%"))),
                List.of(),
                List.of(),
                List.of())));
  }

  private static CharacterLoadout.Equipment equipment(
      String type, String name, CharacterLoadout.EffectGroup group) {
    return new CharacterLoadout.Equipment(
        type,
        name,
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            null, null, null, 4, null, List.of(group), List.of(), List.of(), List.of()));
  }

  private static CombatPowerGemModel.Coefficients coefficients() {
    try (var input = new ClassPathResource("combat-power/gem-coefficients.json").getInputStream()) {
      return new JsonMapper().readValue(input, CombatPowerGemModel.Coefficients.class);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
