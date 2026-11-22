package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.loakraken.refining.domain.AdvancedRefineChain.Ancestor;
import com.loakraken.refining.domain.AdvancedRefineChain.Odds;
import com.loakraken.refining.domain.AdvancedRefineChain.Policy;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 상급 재련 기대 턴 수(계산 검증 명세 2026-11-22 §0·§4). 기대값은 계산 검증의 정확한 사슬(adv-markov) 값이고, 0~10 구간 세 정책은 사용자 확인
 * 화면(11-22, 51 · 40 · 32회)과 맞는다. 시도마다 구슬 +1(옛 규칙)이면 정책 (0, 2)가 50.61이 되어 이 값으로 갈린다.
 */
class AdvancedRefineChainTest {
  static final Odds ODDS =
      new Odds(
          new double[][] {
            {0.80, 0.15, 0.05}, {0.50, 0.30, 0.20}, {0.30, 0.45, 0.25}, {0.00, 0.60, 0.40}
          },
          Map.of(
              Ancestor.GALATUR,
              0.15,
              Ancestor.GELAR,
              0.35,
              Ancestor.KUHUMBAR,
              0.15,
              Ancestor.TEMER,
              0.35),
          Map.of(
              Ancestor.GALATUR, 0.125,
              Ancestor.GELAR, 0.25,
              Ancestor.KUHUMBAR, 0.125,
              Ancestor.TEMER, 0.25,
              Ancestor.NABER, 0.125,
              Ancestor.EBER, 0.125),
          Map.of(
              Ancestor.GALATUR, 0.2,
              Ancestor.GELAR, 0.2,
              Ancestor.KUHUMBAR, 0.2,
              Ancestor.TEMER, 0.2,
              Ancestor.EBER, 0.2));

  @Test
  void 영에서_십_구간의_기대_턴_수는_정확한_사슬_값과_같다() {
    var none = AdvancedRefineChain.expected(ODDS, 0, 0, 1000, new Policy(0, 0, 0));
    assertThat(none.total()).isCloseTo(51.04, within(0.01));
    var bookOnAncestor = AdvancedRefineChain.expected(ODDS, 0, 0, 1000, new Policy(0, 2, 0));
    assertThat(bookOnAncestor.total()).isCloseTo(40.16, within(0.01));
    assertThat(bookOnAncestor.paid()).isCloseTo(25.38, within(0.01));
    assertThat(bookOnAncestor.free()).isCloseTo(3.74, within(0.01));
    assertThat(bookOnAncestor.ancestor()).isCloseTo(11.04, within(0.01));
    assertThat(bookOnAncestor.enhanced()).isZero();
    var bookBoth = AdvancedRefineChain.expected(ODDS, 0, 0, 1000, new Policy(2, 2, 0));
    assertThat(bookBoth.total()).isCloseTo(32.06, within(0.01));
  }

  @Test
  void 이십에서_삼십_구간은_선조_여섯과_강화_선조_턴을_쓴다() {
    var none = AdvancedRefineChain.expected(ODDS, 20, 0, 1000, new Policy(0, 0, 0));
    assertThat(none.paid()).isCloseTo(28.41, within(0.01));
    assertThat(none.free()).isCloseTo(3.23, within(0.01));
    assertThat(none.ancestor()).isCloseTo(12.03, within(0.01));
    assertThat(none.enhanced()).isCloseTo(1.49, within(0.01));
    assertThat(none.total()).isCloseTo(45.16, within(0.01));
    assertThat(AdvancedRefineChain.expected(ODDS, 20, 0, 1000, new Policy(0, 2, 2)).total())
        .isCloseTo(36.59, within(0.01));
  }

  @Test
  void 목표에_이미_닿았으면_턴이_없고_한_단계만이면_구간_끝보다_적다() {
    assertThat(AdvancedRefineChain.expected(ODDS, 0, 1000, 1000, new Policy(0, 0, 0)).total())
        .isZero();
    var oneStage = AdvancedRefineChain.expected(ODDS, 0, 300, 400, new Policy(0, 0, 0));
    assertThat(oneStage.total()).isPositive().isLessThan(10);
  }
}
