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
 * 팔찌 모델(#327): 계산 검증 표본(loalab-bracelet-2026-10-11.json)의 전투력 기여 %(언새늘 12.60·c02 16.86·c01 15.38,
 * 계산 검증 정정)와 추가 피해 +3.50% 한 줄 빼기(3,345.24 → 3,257.54)를 공식 문구 형식 입력으로 맞춘다. 전투 특성 합 T와 도약 P는 표본 줄별
 * 배수에서 나온 값이다.
 */
class CombatPowerBraceletModelTest {
  private static CombatPowerBraceletModel.Coefficients coefficients;

  @BeforeAll
  static void 계수() throws IOException {
    try (var input =
        new ClassPathResource("combat-power/bracelet-coefficients.json").getInputStream()) {
      coefficients = new JsonMapper().readValue(input, CombatPowerBraceletModel.Coefficients.class);
    }
  }

  @Test
  void 언새늘_팔찌_기여와_추가_피해_한_줄_빼기() {
    var model =
        model(
            2469,
            70,
            List.of(
                "특화 +81",
                "전투자원 자연 회복량 +8.00%",
                "치명타 피해 +8.40%",
                "추가 피해 +3.50%",
                "추가 피해가 2.5% 증가한다.\n악마 및 대악마 계열 피해량이 2.5% 증가한다."));
    assertThat(contribution(model)).isEqualByComparingTo("12.60");
    // 추가 피해 +3.50%(중) = 2.6922 → 3,345.24 / 1.026922 = 3,257.54(두 출처 일치 값).
    var removed =
        model.factor(Map.of(3, CombatPowerBraceletModel.option(coefficients, "없음", null)));
    assertThat(
            new BigDecimal("3345.24").multiply(removed.ratio()).setScale(2, RoundingMode.HALF_UP))
        .isEqualByComparingTo("3257.54");
    // 같은 줄을 상(4.00%)으로 바꾸면 1.030768 / 1.026922.
    var upgraded =
        model.factor(Map.of(3, CombatPowerBraceletModel.option(coefficients, "추가 피해", "HIGH")));
    assertThat(upgraded.ratio().setScale(6, RoundingMode.HALF_UP))
        .isEqualByComparingTo(
            new BigDecimal("1.030768").divide(new BigDecimal("1.026922"), 6, RoundingMode.HALF_UP));
    // 0 계수 옵션은 빼도 그대로다.
    var zero = model.factor(Map.of(1, CombatPowerBraceletModel.option(coefficients, "없음", null)));
    assertThat(zero.ratio()).isEqualByComparingTo("1");
  }

  @Test
  void c02_팔찌_기여와_소수점_없는_숫자_표기() {
    var manbae =
        model(
            2601,
            70,
            List.of(
                "치명 +95",
                "신속 +118",
                "치명타 피해가 8.4% 증가한다.\n공격이 치명타로 적중 시 적에게 주는 피해가 1.5% 증가한다.",
                "치명타 피해 +10.00%",
                "추가 피해가 3.5% 증가한다.\n악마 및 대악마 계열 피해량이 2.5% 증가한다."));
    // 전투 특성 두 줄(치명 +95·신속 +118)은 모델 식 (1 + 0.0003·T)대로 함께 뺀다 → 16.86(계산 검증 회신: 표본 16.83은 줄별 배수 곱
    // 오류).
    assertThat(contribution(manbae)).isEqualByComparingTo("16.86");
    // c01: 공식 문구 "3%"·"5%"(소수점 없음)도 3.0·5.0 옵션으로 읽는다.
    var c01 =
        model(
            2625,
            70,
            List.of(
                "신속 +120",
                "특화 +117",
                "적에게 주는 피해가 3% 증가한다.",
                "치명타 적중률이 5% 증가한다.\n공격이 치명타로 적중 시 적에게 주는 피해가 1.5% 증가한다.",
                "백어택 스킬이 적에게 주는 피해가 3% 증가한다."));
    // c01 공식 치특신 합 T = 576 + 1,852 + 197 = 2,625, 도약 70 → 15.38(계산 검증 정정).
    assertThat(contribution(c01)).isEqualByComparingTo("15.38");
  }

  @Test
  void 모르는_옵션은_그_줄만_계산하지_않고_무기_공격력은_W_항으로_넘긴다() {
    var model = model(2469, 70, List.of("특화 +81", "무기 공격력 +7200", "알 수 없는 새 옵션 효과가 3% 증가한다."));
    assertThat(model.lines().get(2).kind()).isEqualTo(CombatPowerBraceletModel.Kind.UNKNOWN);
    assertThat(model.without()).isNull();
    var weapon = model.factor(Map.of(1, CombatPowerBraceletModel.option(coefficients, "없음", null)));
    assertThat(weapon.ratio()).isEqualByComparingTo("1");
    assertThat(weapon.weaponFlatDelta()).isEqualByComparingTo("-7200");
    assertThat(model.factor(Map.of(2, CombatPowerBraceletModel.option(coefficients, "없음", null))))
        .isNull();
    // 힘·민첩·지능 +N은 0이 아니라 재련 모델 S 항으로 넘긴다(계산 검증 #332 회신).
    var main = model(2469, 70, List.of("민첩 +15360"));
    assertThat(main.lines().get(0).kind()).isEqualTo(CombatPowerBraceletModel.Kind.MAIN_STAT);
    assertThat(
            main.factor(Map.of(0, CombatPowerBraceletModel.option(coefficients, "없음", null)))
                .mainStatDelta())
        .isEqualByComparingTo("-15360");
  }

  private static BigDecimal contribution(CombatPowerBraceletModel.Model model) {
    var without = model.without();
    return BigDecimal.ONE
        .subtract(without.ratio())
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static CombatPowerBraceletModel.Model model(
      int combatStats, int leap, List<String> effects) {
    var bracelet =
        new CharacterLoadout.Equipment(
            "팔찌",
            "테스트 팔찌",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                null,
                null,
                4,
                new CharacterLoadout.PointBonus("도약", 18),
                List.of(),
                List.of(),
                List.of(),
                effects.stream()
                    .map(text -> new CharacterLoadout.BraceletEffect(text, EffectGrade.MID))
                    .toList()));
    var loadout =
        new CharacterLoadout(
            List.of(bracelet),
            null,
            null,
            null,
            new CharacterLoadout.ArkPassive(
                "진화", true, List.of(new CharacterLoadout.PassivePoint("도약", leap, "")), List.of()),
            null,
            List.of());
    // 치명·특화·신속 합이 combatStats가 되게 나눠 둔다.
    var stats = new ArrayList<CharacterProfile.Stat>();
    stats.add(new CharacterProfile.Stat("치명", String.valueOf(combatStats - 200), List.of()));
    stats.add(new CharacterProfile.Stat("특화", "100", List.of()));
    stats.add(new CharacterProfile.Stat("신속", "100", List.of()));
    return CombatPowerBraceletModel.of(coefficients, loadout, stats);
  }
}
