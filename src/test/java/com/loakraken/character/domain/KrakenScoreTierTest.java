package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class KrakenScoreTierTest {
  private static final KrakenScoreTier.Table TABLE = table();

  private static KrakenScoreTier.Table table() {
    try (InputStream in = new ClassPathResource("kraken-score/tiers.json").getInputStream()) {
      return JsonMapper.builder().build().readValue(in, KrakenScoreTier.Table.class);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void 컷_이상이면_그_티어이고_진행률은_내림_남은_점수는_소수_둘째_자리다() {
    var bronze = KrakenScoreTier.of(TABLE, 0);
    assertThat(bronze.code()).isEqualTo("BRONZE");
    assertThat(bronze.progressPercent()).isZero();
    assertThat(bronze.nextCode()).isEqualTo("SILVER");

    var almost = KrakenScoreTier.of(TABLE, 2499.999);
    assertThat(almost.code()).isEqualTo("BRONZE");
    assertThat(almost.progressPercent()).isEqualTo(99);
    assertThat(almost.remaining()).isEqualByComparingTo("0.00");

    // 정확히 컷 점수면 그 티어(이상), 진행률 0.
    var silver = KrakenScoreTier.of(TABLE, 2500);
    assertThat(silver.code()).isEqualTo("SILVER");
    assertThat(silver.progressPercent()).isZero();

    // 다이아(5000~6700): (5658.62 − 5000) ÷ 1700 = 38.7% → 38, 남은 점수 1041.38
    var diamond = KrakenScoreTier.of(TABLE, 5658.62);
    assertThat(diamond.code()).isEqualTo("DIAMOND");
    assertThat(diamond.min()).isEqualByComparingTo("5000");
    assertThat(diamond.nextCode()).isEqualTo("MASTER");
    assertThat(diamond.nextMin()).isEqualByComparingTo("6700");
    assertThat(diamond.remaining()).isEqualByComparingTo("1041.38");
    assertThat(diamond.progressPercent()).isEqualTo(38);
  }

  /** 비교 기준 화면이 보이는 정수 남은 점수(표본 5명, 점수는 원본 double 그대로). */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "3977.023079168579, 1023",
    "4757.273158316528, 243",
    "3856.8495309703303, 1143",
    "5286.475085510006, 1414",
    "3506.960646227799, 293"
  })
  void 정수_남은_점수는_비교_기준_화면과_같다(double score, int expected) {
    assertThat(KrakenScoreTier.of(TABLE, score).remainingRounded()).isEqualTo(expected);
  }

  @Test
  void 정수_남은_점수는_원본처럼_둘째_자리_뒤_정수로_두_번_반올림한다() {
    // 원본 화면 Number(diff.toFixed(2)).toFixed(0): 242.497 → 242.50 → 243.
    var tier = KrakenScoreTier.of(TABLE, 5000 - 242.497);
    assertThat(tier.remaining()).isEqualByComparingTo("242.50");
    assertThat(tier.remainingRounded()).isEqualTo(243);
  }

  /** 기대값은 원본 식을 node로 돌린 값(double의 정확한 십진 값 기준 — 1.005는 1.00499…라 1.00). */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "242.497, 242.50, 243",
    "1.005, 1.00, 1",
    "2.675, 2.67, 3",
    "10.495, 10.49, 10",
    "10.505, 10.51, 11",
    "100.5, 100.50, 101",
    "0.495, 0.49, 0"
  })
  void 둘째_자리와_정수_반올림은_원본과_같다(double diff, String twoPlaces, int integer) {
    assertThat(KrakenScoreTier.remaining(diff)).isEqualByComparingTo(twoPlaces);
    assertThat(KrakenScoreTier.screenInteger(diff)).isEqualTo(integer);
  }

  @Test
  void 최상위_티어는_다음_값이_없고_진행률_100이다() {
    var esther = KrakenScoreTier.of(TABLE, 9391.55);
    assertThat(esther.code()).isEqualTo("ESTHER");
    assertThat(esther.nextCode()).isNull();
    assertThat(esther.nextMin()).isNull();
    assertThat(esther.remaining()).isNull();
    assertThat(esther.remainingRounded()).isNull();
    assertThat(esther.progressPercent()).isEqualTo(100);
  }

  @Test
  void 남은_점수는_double로_뺀_뒤_toFixed_2와_같게_반올림한다() {
    // 원본 기대값(JS (컷 − 점수).toFixed(2)): 정확한 십진 뺄셈 뒤 반올림이면 2499.00·1265.44가 되어 다르다.
    assertThat(KrakenScoreTier.of(TABLE, 1.005).remaining()).isEqualByComparingTo("2498.99");
    assertThat(KrakenScoreTier.of(TABLE, 1234.565).remaining()).isEqualByComparingTo("1265.43");
    assertThat(KrakenScoreTier.of(TABLE, 0.005).remaining()).isEqualByComparingTo("2499.99");
    // 2500 − 2499.995 = 0.005000000000109…(double) → 0.01
    assertThat(KrakenScoreTier.of(TABLE, 2499.995).remaining()).isEqualByComparingTo("0.01");
  }

  @Test
  void 음수_NaN_무한대_점수는_티어를_정하지_않는다() {
    for (double bad : new double[] {-1, -0.001, Double.NaN, Double.POSITIVE_INFINITY})
      assertThatThrownBy(() -> KrakenScoreTier.of(TABLE, bad))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 표는_비어_있거나_오름차순이_아니면_만들지_않는다() {
    assertThatThrownBy(() -> new KrakenScoreTier.Table("v", "s", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new KrakenScoreTier.Table(
                    "v",
                    "s",
                    List.of(
                        new KrakenScoreTier.Cut("A", BigDecimal.ZERO),
                        new KrakenScoreTier.Cut("B", BigDecimal.ZERO))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TABLE.ruleVersion()).isEqualTo("kraken-score-v1");
    assertThat(TABLE.tiers()).hasSize(7);
  }
}
