package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterAnalysisTest {
  @Test
  void 미제공_섹션은_0이나_비활성으로_추정하지_않는다() {
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, null));
    assertThat(result.gems()).isNull();
    assertThat(result.cards()).isNull();
    assertThat(result.engravings()).isNull();
    assertThat(result.arkPassive()).isNull();
    assertThat(result.arkGrid()).isNull();
  }

  @Test
  void 빈_보석_목록의_평균과_범위는_null이다() {
    var summary = gems(List.of());
    assertThat(summary.count()).isZero();
    assertThat(summary.unknownLevelCount()).isZero();
    assertThat(summary.averageLevel()).isNull();
    assertThat(summary.minLevel()).isNull();
    assertThat(summary.maxLevel()).isNull();
    assertThat(summary.levelDistribution()).isEmpty();
  }

  @Test
  void 보석_분포는_정렬하고_평균에서_알_수_없는_레벨을_제외한다() {
    var summary = gems(List.of(gem(9), gem(7), gem(9), gem(null), gem(0), gem(-1)));
    assertThat(summary.count()).isEqualTo(6);
    assertThat(summary.unknownLevelCount()).isEqualTo(3);
    assertThat(summary.minLevel()).isEqualTo(7);
    assertThat(summary.maxLevel()).isEqualTo(9);
    assertThat(summary.averageLevel()).isEqualByComparingTo("8.33");
    assertThat(summary.levelDistribution())
        .containsExactly(
            new CharacterAnalysis.LevelCount(7, 1), new CharacterAnalysis.LevelCount(9, 2));
  }

  @Test
  void 모든_레벨이_알_수_없으면_평균_0을_만들지_않는다() {
    var summary = gems(List.of(gem(null), gem(-1)));
    assertThat(summary.count()).isEqualTo(2);
    assertThat(summary.unknownLevelCount()).isEqualTo(2);
    assertThat(summary.averageLevel()).isNull();
  }

  @Test
  void 카드_각성은_확인_가능한_값만_합산한다() {
    var cards =
        new CharacterLoadout.Cards(
            List.of(card(5, 5), card(0, 5), card(null, 5), card(-1, 5), card(6, 5)), List.of());
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, cards, null, null))
            .cards();
    assertThat(result.count()).isEqualTo(5);
    assertThat(result.unknownAwakeningCount()).isEqualTo(3);
    assertThat(result.knownAwakeningTotal()).isEqualTo(5);
  }

  @Test
  void 패시브_비활성과_포인트_0은_유효한_관측값이다() {
    var passive =
        new CharacterLoadout.ArkPassive(
            "패시브",
            false,
            List.of(
                new CharacterLoadout.PassivePoint("진화", 0, null),
                new CharacterLoadout.PassivePoint("깨달음", -1, null)),
            List.of());
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, passive, null))
            .arkPassive();
    assertThat(result.enabled()).isFalse();
    assertThat(result.points().getFirst().value()).isZero();
    assertThat(result.points().getLast().value()).isNull();
  }

  @Test
  void 그리드_활성_비활성_미확인_보석을_구분한다() {
    var slot =
        new CharacterLoadout.GridSlot(
            0, null, "코어", 14, "유물", List.of(gridGem(true), gridGem(false), gridGem(null)));
    var grid = new CharacterLoadout.ArkGrid(List.of(slot), List.of());
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, grid))
            .arkGrid();
    assertThat(result.coreCount()).isEqualTo(1);
    assertThat(result.activeGemCount()).isEqualTo(1);
    assertThat(result.inactiveGemCount()).isEqualTo(1);
    assertThat(result.unknownGemCount()).isEqualTo(1);
  }

  private CharacterAnalysis.GemSummary gems(List<CharacterLoadout.Gem> gems) {
    return CharacterAnalysis.from(
            new CharacterLoadout(
                List.of(), null, new CharacterLoadout.Gems(gems, null), null, null, null))
        .gems();
  }

  private CharacterLoadout.Gem gem(Integer level) {
    return new CharacterLoadout.Gem(0, "보석", null, level, "전설");
  }

  private CharacterLoadout.Card card(Integer count, Integer total) {
    return new CharacterLoadout.Card(0, "카드", null, count, total, "전설");
  }

  private CharacterLoadout.GridGem gridGem(Boolean active) {
    return new CharacterLoadout.GridGem(0, null, active, "희귀");
  }
}
