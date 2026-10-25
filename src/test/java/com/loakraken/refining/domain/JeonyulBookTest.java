package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** #218 B: 전율 책 증가 = 그 단계 기본 성공률(계산 검증 2026-10-10, 비교 기준 C·E 일치). */
class JeonyulBookTest {
  @Test
  void 전율_책은_12_19단계에서_그_단계_기본_성공률만큼_더하고_20단계부터는_없다() throws IOException {
    RefiningRules rules;
    try (var input =
        JeonyulBookTest.class.getResourceAsStream("/refining/t4-refining-rules.json")) {
      rules = JsonMapper.builder().build().readValue(input, RefiningRules.class);
    }
    assertThat(rules.ruleVersion()).isEqualTo("t4-refining-v4");
    var weapon = rules.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.WEAPON);
    var armor = rules.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.ARMOR);
    for (int stage = 12; stage <= 19; stage++) {
      var rule = weapon.stage(stage);
      assertThat(rule.book().rateBp()).isEqualTo(rule.baseRateBp());
      assertThat(rule.book().name()).startsWith("야금술 : 전율 [" + (stage <= 15 ? "12-15" : "16-19"));
      assertThat(armor.stage(stage).book().name()).startsWith("재봉술 : 전율");
    }
    assertThat(weapon.stage(12).book().rateBp()).isEqualTo(500);
    assertThat(weapon.stage(17).book().rateBp()).isEqualTo(300);
    assertThat(weapon.stage(20).book()).isNull();
    // 첫 시도: 기본 3% + 숨결 + 책 3%(비교 기준 E 표본 18→19 무기 숨결 + 책 9%)
    var plan =
        RefiningPlan.calculate(
            weapon,
            RefiningRules.Grade.JEONYUL,
            new RefiningPlan.Options(18, 19, true, true, false, 0, 0));
    assertThat(plan.stages().getFirst().firstAttemptSuccessRateBp()).isEqualTo(900);
  }

  @Test
  void 전율_17_19단계_숨결은_25개_0_12퍼센트이고_다른_단계는_그대로다() throws IOException {
    // t4-refining-v4(계산 검증 2026-10-26): 게임 재련 화면(무기 17→18·18→19: 숨결 최대 25개, 개당 0.120%, 최대 +3.000%)과
    // 비교 기준 규칙 데이터(방어구·무기 17~19) 일치. v3의 20개 × 0.15%는 틀림(최대 3%는 같고 개수·한 개 증가량이 다름).
    var rules = rules();
    for (var type : RefiningRules.EquipmentType.values()) {
      var track = rules.track(RefiningRules.Grade.JEONYUL, type);
      for (int stage = 17; stage <= 19; stage++) {
        assertThat(track.stage(stage).breath().maxCount()).as(type + " " + stage).isEqualTo(25);
        assertThat(track.stage(stage).breath().rateBp()).as(type + " " + stage).isEqualTo(12);
      }
      assertThat(track.stage(16).breath().maxCount()).isEqualTo(20);
      assertThat(track.stage(16).breath().rateBp()).isEqualTo(20);
      assertThat(track.stage(20).breath().maxCount()).isEqualTo(25);
      assertThat(track.stage(20).breath().rateBp()).isEqualTo(6);
    }
  }

  /**
   * 숨결 최대(MAX) 계획: 첫 시도 기본 3% + 숨결 25 × 0.12% = 6%, 숨결 기대 수량 = 25 × 기대 시도(최악은 25 × 최대 시도 − 확정 회차).
   */
  @Test
  void 전율_17_19단계_숨결_최대는_시도마다_25개이고_첫_시도_6퍼센트다() throws IOException {
    var rules = rules();
    for (var type : RefiningRules.EquipmentType.values()) {
      var track = rules.track(RefiningRules.Grade.JEONYUL, type);
      var breathName = track.stage(17).breath().name();
      for (int target = 17; target <= 19; target++) {
        var plan =
            RefiningPlan.calculate(
                track,
                RefiningRules.Grade.JEONYUL,
                new RefiningPlan.Options(target - 1, target, true, false, false, 0, 0));
        var stage = plan.stages().getFirst();
        assertThat(stage.firstAttemptSuccessRateBp()).as(type + " " + target).isEqualTo(600);
        var breaths =
            stage.expectedMaterials().stream()
                .filter(q -> q.name().equals(breathName))
                .findFirst()
                .orElseThrow()
                .amount();
        var expectedAttempts = stage.expectedAttempts();
        // 확정 회차(장인의 기운 100%)에는 숨결을 쓰지 않아 25 × 기대 시도보다 작거나 같고, 25 × (기대 시도 − 1)보다 크거나 같다.
        assertThat(breaths.doubleValue())
            .as(type + " " + target)
            .isLessThanOrEqualTo(25 * expectedAttempts.doubleValue() + 0.01)
            .isGreaterThanOrEqualTo(25 * (expectedAttempts.doubleValue() - 1) - 0.01);
      }
    }
  }

  /**
   * 회차별 최적(OPTIMAL): 숨결은 0~25개를 고르고 한 개마다 0.12%씩 오른다(첫 회차 확률 = 기본 3% + 숨결 수 × 0.12%). 일부만 쓰는 회차도
   * 나온다(회차별 선택은 path의 "숨결 N").
   */
  @Test
  void 전율_17_19단계_회차별_최적은_숨결을_0_12퍼센트씩_고른다() throws IOException {
    var rules = rules();
    var rule =
        rules.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.WEAPON).stage(18);
    var count = java.util.regex.Pattern.compile("숨결 (\\d+)");
    boolean partial = false;
    for (int price : new int[] {50, 100, 200, 400, 800, 1600, 3200}) {
      var prices = new java.util.HashMap<String, Number>();
      for (var material : rule.materials()) prices.put(material.name(), 1);
      prices.put(RefiningPlan.SHARD, 0.1);
      prices.put(rule.breath().name(), price);
      var result = RefiningOptimal.calculate(rule, 1, prices, true, false);
      assertThat(result).isNotNull();
      for (int i = 0; i < result.path().size(); i++) {
        var matcher = count.matcher(result.path().get(i));
        int breaths = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        assertThat(breaths).as("숨결 " + price + " " + result.path()).isBetween(0, 25);
        if (i == 0)
          assertThat(result.firstRateBp()).as("숨결 " + price).isEqualTo(300 + breaths * 12);
        if (breaths > 0 && breaths < 25) partial = true;
      }
    }
    assertThat(partial).as("숨결을 일부만 쓰는 회차").isTrue();
  }

  private static RefiningRules rules() throws IOException {
    try (var input =
        JeonyulBookTest.class.getResourceAsStream("/refining/t4-refining-rules.json")) {
      return JsonMapper.builder().build().readValue(input, RefiningRules.class);
    }
  }
}
