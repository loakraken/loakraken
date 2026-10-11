package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 품질·각인·스톤 B단계(#275) 근거 고정: 계산 검증 표본(#253·#259·#263, 5명 38건)에서 식이 확정된 27건을 기대값으로 읽어 식 값과 같고 목표값과
 * ±0.01 안인지 본다. 각 캐릭터의 현재 각인서·스톤 레벨은 표본의 setup 문구에서 읽는다.
 */
class CombatPowerEquipmentModelTest {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final Pattern BOOK = Pattern.compile("^(.+?) \\+(\\d)$");
  private static final Pattern STONE = Pattern.compile("^(.+?) (\\d)$");
  private static final CombatPowerEquipmentModel.Coefficients COEFFICIENTS = coefficients();

  @Test
  void 교차_검증_표본의_확정_27건과_0_01_안에서_같다() throws Exception {
    JsonNode root;
    try (var input =
        new ClassPathResource(
                "verification/combat-power/loalab-equip-engraving-deltas-2026-10-10.json")
            .getInputStream()) {
      root = new JsonMapper().readTree(input);
    }
    int ok = 0;
    for (var character : root.path("characters")) {
      var books = parse(character.at("/setup/engravings").asText(), BOOK);
      var stones = parse(character.at("/setup/stone").asText(), STONE);
      var before = new BigDecimal(character.path("combatPowerAtMeasurement").asText());
      for (var sample : character.path("cases")) {
        if (!"OK".equals(sample.path("formulaStatus").asText())) continue;
        var target = sample.path("target").asText();
        int from = sample.path("from").asInt();
        int to = sample.path("to").asInt();
        var ratio =
            switch (sample.path("kind").asText()) {
              case "EQUIPMENT_QUALITY" ->
                  "무기".equals(target)
                      ? CombatPowerEquipmentModel.weaponQualityRatio(
                          CombatPowerEquipmentModel.weaponAddDamage(from),
                          CombatPowerEquipmentModel.weaponAddDamage(to))
                      : BigDecimal.ONE; // 방어구 품질은 배수 1
              case "ENGRAVING_LEVEL" -> {
                int stone = stones.getOrDefault(target, 0);
                yield factor(target, to, stone).divide(factor(target, from, stone), MATH);
              }
              case "STONE_LEVEL" -> {
                int book = books.getOrDefault(target, 0);
                yield factor(target, book, to).divide(factor(target, book, from), MATH);
              }
              default -> throw new IllegalStateException(sample.toString());
            };
        var actual = before.multiply(ratio, MATH).setScale(2, RoundingMode.HALF_UP);
        var label =
            character.path("id").asText() + " " + sample.path("kind").asText() + " " + target;
        assertThat(actual)
            .as(label)
            .isEqualByComparingTo(new BigDecimal(sample.path("formula").asText()));
        assertThat(
                actual.subtract(new BigDecimal(sample.path("expectedTargetSite").asText())).abs())
            .as(label)
            .isLessThanOrEqualTo(new BigDecimal("0.01"));
        ok++;
      }
    }
    assertThat(ok).isEqualTo(27);
  }

  @Test
  void 공식_각인_설명값이_계수표와_같은지_본다() {
    // 언새늘 공식 설명(2026-10-10): 원한 L0 S2 21.75%, 아드레날린 L3 S3 1.73%·18.50%, 돌격대장 L0 40.00%.
    assertThat(matches(0, 2, "원한", "보스 및 레이드 몬스터에게 주는 피해가 21.75% 증가하지만, 받는 피해가 20.00% 증가한다."))
        .isTrue();
    assertThat(
            matches(
                3,
                3,
                "아드레날린",
                "스킬 사용 후 6초 동안 공격력이 1.73% 증가하며 (최대 6중첩) 해당 효과가 최대 중첩 도달 시 치명타 적중률이 추가로 18.50% 증가한다."))
        .isTrue();
    assertThat(matches(0, null, "돌격대장", "이동속도 증가량의 40.00% 만큼 적에게 주는 피해량이 증가한다.")).isTrue();
    // 설명값이 표와 다르면(예: 패치로 값이 바뀜) 계산하지 않는다.
    assertThat(matches(0, 2, "원한", "보스 및 레이드 몬스터에게 주는 피해가 22.00% 증가")).isFalse();
    // 효과 수치(첫째 % 값)가 표와 다르면, 뒤의 다른 수치가 우연히 같아도 통과하지 않는다(#290). 저주받은 인형 L0 S4 = 20.00.
    assertThat(matches(0, 4, "저주받은 인형", "공격력이 25.00% 증가하지만, 받는 피해가 20.00% 증가한다.")).isFalse();
    assertThat(matches(0, 4, "저주받은 인형", "공격력이 20.00% 증가하지만, 받는 피해가 20.00% 증가한다.")).isTrue();
    // 유물이 아니면 표가 없다.
    assertThat(
            CombatPowerEquipmentModel.matchesOfficial(
                COEFFICIENTS,
                new CharacterLoadout.PassiveEngraving(2, "전설", 4, "원한", "피해가 21.75% 증가")))
        .isFalse();
  }

  @Test
  void 각인_6종의_공식_설명_원문에서_효과_수치_위치를_맞게_읽는다() {
    // 계산 검증 2026-10-11 10:38 실호출 원문(태그 제거). 질량 증가만 v가 둘째 % 값이다(#290).
    var hit = "공격 타입이 백 어택 및 헤드 어택에 해당되지 않는 공격의 피해가 %s%% 증가한다. 각성기는 해당 효과가 적용되지 않는다.";
    assertThat(matches(0, null, "타격의 대가", hit.formatted("14.00"))).isTrue();
    assertThat(matches(4, null, "타격의 대가", hit.formatted("17.00"))).isTrue();
    assertThat(matches(4, 2, "타격의 대가", hit.formatted("20.75"))).isTrue();
    var mass = "공격속도가 10.00%% 감소하지만, 적에게 주는 피해가 %s%% 증가한다.";
    assertThat(matches(4, null, "질량 증가", mass.formatted("19.00"))).isTrue();
    assertThat(matches(4, 2, "질량 증가", mass.formatted("22.75"))).isTrue();
    assertThat(matches(4, 2, "질량 증가", mass.formatted("22.00"))).isFalse();
    assertThat(matches(0, 2, "원한", "보스 및 레이드 몬스터에게 주는 피해가 21.75% 증가하지만, 받는 피해가 20.00% 증가한다."))
        .isTrue();
    assertThat(matches(4, 3, "저주받은 인형", "적에게 주는 피해가 22.25% 증가하지만, 받는 모든 회복 효과가 25.00% 감소한다."))
        .isTrue();
    assertThat(matches(4, 3, "돌격대장", "이동속도 증가량의 61.20% 만큼 적에게 주는 피해량이 증가한다.")).isTrue();
    assertThat(
            matches(
                4,
                4,
                "아드레날린",
                "이동기 및 기본공격을 제외한 스킬 사용 후 6초 동안 공격력이 1.85% 증가하며 (최대 6중첩) 해당 효과가 최대 중첩 도달 시 치명타 적중률이 추가로 20.00% 증가한다. 해당 효과는 스킬 취소에 따른 재사용 대기시간 감소가 적용되는 경우, 스킬 종료 후 적용된다."))
        .isTrue();
  }

  @Test
  void 확인하지_않은_조합은_배수를_주지_않는다() {
    // 돌격대장 스톤 1·예리한 둔기는 계산하지 않는다.
    assertThat(CombatPowerEquipmentModel.engravingFactor(COEFFICIENTS, "돌격대장", 4, 1)).isNull();
    assertThat(CombatPowerEquipmentModel.engravingFactor(COEFFICIENTS, "예리한 둔기", 4, 3)).isNull();
    assertThat(CombatPowerEquipmentModel.supportsEngraving(COEFFICIENTS, "예리한 둔기")).isFalse();
    // 무기 추가 피해 = 10 + 20 × (품질/100)², 반올림 없음(계산 검증 10-11 17:25). 툴팁은 올림 값(95 → 28.06)이라 쓰지 않는다.
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(99)).isEqualByComparingTo("29.602");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(95)).isEqualByComparingTo("28.05");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(71)).isEqualByComparingTo("20.082");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(70)).isEqualByComparingTo("19.80");
    // 이전 표와 같은 값: 100·90·85·75.
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(100)).isEqualByComparingTo("30");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(90)).isEqualByComparingTo("26.2");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(85)).isEqualByComparingTo("24.45");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(75)).isEqualByComparingTo("21.25");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(0)).isEqualByComparingTo("10");
    assertThat(CombatPowerEquipmentModel.weaponAddDamage(101)).isNull();
    // 스톤 합 5~7 안에서만 [레벨 보너스]가 그대로다.
    assertThat(CombatPowerEquipmentModel.stoneBonusStable(COEFFICIENTS, 5, 7)).isTrue();
    assertThat(CombatPowerEquipmentModel.stoneBonusStable(COEFFICIENTS, 5, 4)).isFalse();
    assertThat(CombatPowerEquipmentModel.stoneBonusStable(COEFFICIENTS, 7, 8)).isFalse();
  }

  @Test
  void 지금_무기_추가_피해는_품질로_계산한다() {
    var detail =
        new CharacterLoadout.EquipmentDetail(
            19,
            100,
            null,
            4,
            null,
            java.util.List.of(
                new CharacterLoadout.EffectGroup("기본 효과", java.util.List.of("무기 공격력 +208130")),
                new CharacterLoadout.EffectGroup("추가 효과", java.util.List.of("추가 피해 +30.00%"))),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of());
    assertThat(CombatPowerEquipmentModel.currentWeaponAddDamage(detail))
        .isEqualByComparingTo("30.00");
  }

  private static boolean matches(int book, Integer stone, String name, String description) {
    return CombatPowerEquipmentModel.matchesOfficial(
        COEFFICIENTS, new CharacterLoadout.PassiveEngraving(stone, "유물", book, name, description));
  }

  private static BigDecimal factor(String name, int book, int stone) {
    var value = CombatPowerEquipmentModel.engravingFactor(COEFFICIENTS, name, book, stone);
    assertThat(value).as(name + " " + book + "/" + stone).isNotNull();
    return value;
  }

  private static Map<String, Integer> parse(String text, Pattern pattern) {
    var result = new HashMap<String, Integer>();
    for (var part : text.split(",")) {
      var matcher = pattern.matcher(part.strip());
      if (matcher.matches()) result.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
    }
    return result;
  }

  private static CombatPowerEquipmentModel.Coefficients coefficients() {
    try (var input =
        new ClassPathResource("combat-power/equipment-engraving-coefficients.json")
            .getInputStream()) {
      return new JsonMapper().readValue(input, CombatPowerEquipmentModel.Coefficients.class);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
