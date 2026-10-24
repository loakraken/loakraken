package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 회차별 최적 전략(#284): 단계 전체에 한 조합을 고정하는 어떤 전략보다 같거나 싸다. */
class RefiningOptimalTest {
  private static final RefiningRules RULES = rules();

  @Test
  void 숨결_책을_못_쓰면_기본_전략의_기대_비용과_같다() {
    var track = RULES.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.WEAPON);
    var prices = prices(track.stage(19), 1);
    var optimal = RefiningOptimal.calculate(track.stage(19), 1, prices, false, false);
    assertThat(optimal.expectedGold())
        .isCloseTo(
            single(track, RefiningRules.Grade.JEONYUL, 19, false, false, prices),
            org.assertj.core.data.Offset.offset(1.0));
    assertThat(optimal.usesBreath()).isFalse();
    assertThat(optimal.usesBook()).isFalse();
  }

  @Test
  void 어떤_고정_전략보다_같거나_싸다() {
    for (var grade : RefiningRules.Grade.values())
      for (var type : RefiningRules.EquipmentType.values()) {
        var track = RULES.track(grade, type);
        for (int stage : new int[] {12, 15, 18, 19, 21, 24}) {
          if (stage < track.minTargetStage() || stage > track.maxTargetStage()) continue;
          var rule = track.stage(stage);
          // 숨결이 비쌀 때와 쌀 때 둘 다 본다.
          for (double breathScale : new double[] {0.2, 1, 5}) {
            var prices = prices(rule, breathScale);
            var optimal = RefiningOptimal.calculate(rule, 1, prices, true, true);
            double bestSingle = Double.MAX_VALUE;
            for (boolean breath : new boolean[] {false, true})
              for (boolean book : new boolean[] {false, true}) {
                if (breath && rule.breath() == null || book && rule.book() == null) continue;
                bestSingle =
                    Math.min(bestSingle, single(track, grade, stage, breath, book, prices));
              }
            assertThat(optimal.expectedGold())
                .as(grade + " " + type + " " + stage + " ×" + breathScale)
                .isLessThanOrEqualTo(bestSingle * (1 + 1e-5));
            assertThat(optimal.path()).isNotEmpty();
          }
        }
      }
  }

  @Test
  void 계산_검증_기준_전율_무기_18에서_19는_숨결_개수를_회차마다_고른다() {
    // 계산 검증 #287 댓글(2026-10-11 10:08 단가): 숨결 0~20 × 책, 장인의 기운 0.001%p, 성장 파편 105,000 포함.
    var rule =
        RULES.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.WEAPON).stage(19);
    var prices =
        Map.of(
            "운명의 파괴석 결정",
            new BigDecimal("8.37"),
            "위대한 운명의 돌파석",
            new BigDecimal("34"),
            "상급 아비도스 융화 재료",
            new BigDecimal("270"),
            RefiningPlan.SHARD,
            new BigDecimal("0.083"),
            "용암의 숨결",
            new BigDecimal("479"),
            "야금술 : 전율 [16-19]",
            new BigDecimal("14870"));
    double growth = 105_000 * 0.083;
    var withBook = RefiningOptimal.calculate(rule, 1, prices, true, true);
    // 10-25: 장인의 기운을 게임처럼 0.01%p에서 버린 뒤 값(전에는 0.001%p 반올림으로 616,461.9·숨결 4). 기운이 조금 덜 쌓여
    // 확정 성공 전 회차가 비싸진다.
    assertThat(withBook.expectedGold() + growth)
        .isCloseTo(616_506.9, org.assertj.core.data.Offset.offset(1.0));
    // 20회째(실패 19번 뒤)는 숨결 5개만 쓴다.
    assertThat(withBook.path().get(19)).isEqualTo("숨결 5");
    var withoutBook = RefiningOptimal.calculate(rule, 1, prices, true, false);
    assertThat(withoutBook.expectedGold() + growth)
        .isCloseTo(652_272.6, org.assertj.core.data.Offset.offset(1.0));
    assertThat(withoutBook.usesBook()).isFalse();
  }

  @Test
  void 기본_재료_시세가_없으면_계산하지_않는다() {
    var track = RULES.track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    assertThat(RefiningOptimal.calculate(track.stage(15), 1, Map.of(), true, true)).isNull();
  }

  /**
   * 단계 전체에 한 조합을 고정했을 때의 기대 골드(RefiningPlan 기대 재료 × 시세 + 골드). RefiningPlan은 기대 수량을 반올림해 내므로 비교는 상대
   * 오차 1e-5 안에서 한다.
   */
  private static double single(
      RefiningRules.Track track,
      RefiningRules.Grade grade,
      int stage,
      boolean breath,
      boolean book,
      Map<String, BigDecimal> prices) {
    var plan =
        RefiningPlan.calculate(
            track, grade, new RefiningPlan.Options(stage - 1, stage, breath, book, false, 0, 0));
    var stagePlan = plan.stages().getFirst();
    double gold = stagePlan.expectedGold().doubleValue();
    for (var quantity : stagePlan.expectedMaterials())
      gold += quantity.amount().doubleValue() * prices.get(quantity.name()).doubleValue();
    return gold;
  }

  private static Map<String, BigDecimal> prices(RefiningRules.Stage rule, double breathScale) {
    var prices = new HashMap<String, BigDecimal>();
    for (var material : rule.materials()) prices.put(material.name(), BigDecimal.valueOf(3));
    prices.put(RefiningPlan.SHARD, new BigDecimal("0.2"));
    if (rule.breath() != null)
      prices.put(rule.breath().name(), BigDecimal.valueOf(400 * breathScale));
    if (rule.book() != null) prices.put(rule.book().name(), BigDecimal.valueOf(20_000));
    return prices;
  }

  private static RefiningRules rules() {
    try (var input =
        RefiningOptimalTest.class.getResourceAsStream("/refining/t4-refining-rules.json")) {
      return JsonMapper.builder().build().readValue(input, RefiningRules.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
