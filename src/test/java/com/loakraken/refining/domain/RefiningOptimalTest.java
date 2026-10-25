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
  void 업화_방어구_10에서_12는_단계마다_회차별_최적의_합이다() {
    // 계산 검증 10-25, 비교 기준 B 같은 시각 시세(2026-10-24T15:54:51Z). 회차당 비용은 B와 같다(11단계 2,920, 12단계 3,056).
    // 우리(공식 규칙): 장인의 기운 0.01%p 버림, 숨결 일부 사용 가능 → 10→11 19,382, 10→12 39,572.
    // B: 10→11 19,362, 10→12 39,591 — 기운 0.1%p 반올림과 정책 차이(B 12단계는 2회째 책·숨결 없음, 우리는 1회째 책·마지막 회차 숨결
    // 1개).
    // 증명된 차이로 두고 B와는 회차당 비용·단계 합 방식만 맞춘다.
    var track = RULES.track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    var prices =
        Map.of(
            "운명의 수호석",
            new BigDecimal("0.32"),
            "운명의 돌파석",
            new BigDecimal("5"),
            "아비도스 융화 재료",
            new BigDecimal("197"),
            RefiningPlan.SHARD,
            new BigDecimal("0.092"),
            "빙하의 숨결",
            new BigDecimal("430"),
            "재봉술 : 업화 [11-14]",
            new BigDecimal("2000"));
    var eleven = RefiningOptimal.calculate(track.stage(11), 1, prices, true, true);
    var twelve = RefiningOptimal.calculate(track.stage(12), 1, prices, true, true);
    assertThat(Math.round(eleven.expectedGold())).isEqualTo(19_382);
    assertThat(Math.round(eleven.expectedGold() + twelve.expectedGold())).isEqualTo(39_572);
    assertThat(eleven.usesBook()).isTrue();
    // 업화 18단계까지 성장 지원 20%(재련 골드만, 공식 공지 13307): 18,095 + 18,866.
    var elevenSupported = RefiningOptimal.calculate(track.stage(11), 0.8, prices, true, true);
    var twelveSupported = RefiningOptimal.calculate(track.stage(12), 0.8, prices, true, true);
    assertThat(Math.round(elevenSupported.expectedGold() + twelveSupported.expectedGold()))
        .isEqualTo(36_962);
  }

  @Test
  void 기대_수량으로_다시_더한_골드는_기대_골드와_같다() {
    // 스펙업 줄 상세(#585): 내역 합 = 목록 비용이 되려면 최적 정책의 기대 시도·숨결·책 수가 기대 골드와 맞아야 한다.
    for (var grade : RefiningRules.Grade.values())
      for (var type : RefiningRules.EquipmentType.values()) {
        var track = RULES.track(grade, type);
        for (int stage : new int[] {12, 15, 19, 24}) {
          if (stage < track.minTargetStage() || stage > track.maxTargetStage()) continue;
          var rule = track.stage(stage);
          for (double breathScale : new double[] {0.2, 1, 5}) {
            var prices = prices(rule, breathScale);
            var optimal = RefiningOptimal.calculate(rule, 1, prices, true, true);
            double base = rule.gold();
            for (var material : rule.materials())
              base += material.count() * prices.get(material.name()).doubleValue();
            base += rule.shard() * prices.get(RefiningPlan.SHARD).doubleValue();
            double again = optimal.expectedAttempts() * base;
            if (rule.breath() != null)
              again += optimal.expectedBreaths() * prices.get(rule.breath().name()).doubleValue();
            if (rule.book() != null)
              again += optimal.expectedBooks() * prices.get(rule.book().name()).doubleValue();
            assertThat(again)
                .as(grade + " " + type + " " + stage + " ×" + breathScale)
                .isCloseTo(
                    optimal.expectedGold(),
                    org.assertj.core.data.Offset.offset(1e-6 * optimal.expectedGold()));
            assertThat(optimal.expectedAttempts()).isGreaterThanOrEqualTo(1);
          }
        }
      }
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

  /** 비교 기준 B 화면과 같은 시각의 재료 1개 값(계산 검증 캡처 2026-10-24T23:39:31Z). */
  private static Map<String, Number> vambracePrices(boolean breaths) {
    var prices = new HashMap<String, Number>();
    prices.put("운명의 파괴석 결정", 8.92);
    prices.put("운명의 수호석 결정", 1.77);
    prices.put("위대한 운명의 돌파석", 18);
    prices.put("상급 아비도스 융화 재료", 268);
    prices.put(RefiningPlan.SHARD, 0.091);
    if (breaths) {
      prices.put("빙하의 숨결", 414);
      prices.put("용암의 숨결", 476);
    }
    return prices;
  }

  @Test
  void 팔_보호구는_숨결을_사지_않으면_비교_기준과_단계마다_같다() {
    // 계산 검증 10-25: B의 기본(보유 재료 미반영)은 숨결을 사지 않는다. 같은 시세에서 16 → 21 단계 합 4,340,891(B 화면 434.09만).
    var rules = vambraceRules();
    double[] expected = {677_956, 704_673, 732_753, 761_402, 1_464_107};
    double total = 0;
    for (int stage = 17; stage <= 21; stage++) {
      var result = RefiningOptimal.calculate(rules.stage(stage), vambracePrices(false));
      assertThat(result.usesBreath()).isFalse();
      assertThat(result.expectedGold())
          .as("%d단계", stage)
          .isCloseTo(expected[stage - 17], org.assertj.core.data.Offset.offset(0.5));
      total += result.expectedGold();
    }
    assertThat(Math.round(total)).isEqualTo(4_340_891L);
  }

  @Test
  void 팔_보호구는_시도마다_싼_숨결부터_골라_더_싸게_푼다() {
    // PM 총괄 10-25: 숨결을 사는 편이 싸면 산다(증명된 차이). 빙하 414 < 용암 476이라 빙하부터.
    var rules = vambraceRules();
    var withBreath = RefiningOptimal.calculate(rules.stage(17), vambracePrices(true));
    var without = RefiningOptimal.calculate(rules.stage(17), vambracePrices(false));
    assertThat(withBreath.usesBreath()).isTrue();
    assertThat(withBreath.expectedGold())
        .isCloseTo(677_293.26, org.assertj.core.data.Offset.offset(0.01))
        .isLessThan(without.expectedGold());
    assertThat(withBreath.expectedBreaths())
        .isCloseTo(9.127639, org.assertj.core.data.Offset.offset(1e-5));
    assertThat(withBreath.expectedSecondBreaths()).isBetween(0.0, withBreath.expectedBreaths());
    double total = 0;
    for (int stage = 17; stage <= 21; stage++)
      total += RefiningOptimal.calculate(rules.stage(stage), vambracePrices(true)).expectedGold();
    assertThat(total).isCloseTo(4_335_693.74, org.assertj.core.data.Offset.offset(0.05));
  }

  @Test
  void 팔_보호구_숨결_증가량이_다르면_풀지_않는다() {
    var stage = vambraceRules().stage(17);
    var odd =
        new VambraceRules.Stage(
            stage.targetStage(),
            stage.baseRateBp(),
            stage.materials(),
            stage.shard(),
            stage.gold(),
            java.util.List.of(
                new VambraceRules.Breath("용암의 숨결", 30, 5),
                new VambraceRules.Breath("빙하의 숨결", 30, 10)));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> RefiningOptimal.calculate(odd, vambracePrices(true)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static VambraceRules vambraceRules() {
    try (var input =
        RefiningOptimalTest.class.getResourceAsStream("/refining/t4-vambrace-rules.json")) {
      return JsonMapper.builder().build().readValue(input, VambraceRules.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
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
