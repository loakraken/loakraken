package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class ScoreATierTest {
  private static final ScoreATier.Table TABLE = table();

  private static ScoreATier.Table table() {
    try (InputStream in = new ClassPathResource("score-a/tiers.json").getInputStream()) {
      return JsonMapper.builder().build().readValue(in, ScoreATier.Table.class);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void 컷_이상이면_그_티어이고_진행률은_내림_남은_점수는_소수_둘째_자리다() {
    var bronze = ScoreATier.of(TABLE, BigDecimal.ZERO);
    assertThat(bronze.code()).isEqualTo("BRONZE");
    assertThat(bronze.progressPercent()).isZero();
    assertThat(bronze.nextCode()).isEqualTo("SILVER");

    var almost = ScoreATier.of(TABLE, new BigDecimal("2499.999"));
    assertThat(almost.code()).isEqualTo("BRONZE");
    assertThat(almost.progressPercent()).isEqualTo(99);
    assertThat(almost.remaining()).isEqualByComparingTo("0.00");

    assertThat(ScoreATier.of(TABLE, new BigDecimal("2500")).code()).isEqualTo("SILVER");

    // 다이아(5000~6700): (5658.62 − 5000) ÷ 1700 = 38.7% → 38, 남은 점수 1041.38
    var diamond = ScoreATier.of(TABLE, new BigDecimal("5658.62"));
    assertThat(diamond.code()).isEqualTo("DIAMOND");
    assertThat(diamond.min()).isEqualByComparingTo("5000");
    assertThat(diamond.nextCode()).isEqualTo("MASTER");
    assertThat(diamond.nextMin()).isEqualByComparingTo("6700");
    assertThat(diamond.remaining()).isEqualByComparingTo("1041.38");
    assertThat(diamond.progressPercent()).isEqualTo(38);
  }

  @Test
  void 최상위_티어는_다음_값이_없고_진행률_100이다() {
    var esther = ScoreATier.of(TABLE, new BigDecimal("9391.55"));
    assertThat(esther.code()).isEqualTo("ESTHER");
    assertThat(esther.nextCode()).isNull();
    assertThat(esther.nextMin()).isNull();
    assertThat(esther.remaining()).isNull();
    assertThat(esther.progressPercent()).isEqualTo(100);
  }

  @Test
  void 표는_비어_있거나_오름차순이_아니면_만들지_않는다() {
    assertThatThrownBy(() -> new ScoreATier.Table("v", "s", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ScoreATier.Table(
                    "v",
                    "s",
                    List.of(
                        new ScoreATier.Cut("A", BigDecimal.ZERO),
                        new ScoreATier.Cut("B", BigDecimal.ZERO))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TABLE.ruleVersion()).isEqualTo("score-a-v1");
    assertThat(TABLE.tiers()).hasSize(7);
  }
}
