package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 스톤 목표 조합의 배치: 점수가 더 오르는 쪽, 같으면 앞 배치(목록과 추천 순서가 같은 배치를 쓴다, PM 총괄 11-15). */
class SpecUpStoneOrientationTest {
  private static final int[] NINE_SEVEN = {9, 7, 3, 2};

  private static SpecUpKrakenScore scorer(double first, double flipped) {
    var scorer = mock(SpecUpKrakenScore.class);
    when(scorer.delta(any(SpecUpKrakenScore.Context.class), any(SpecUpKrakenScore.Edit.class)))
        .thenReturn(null);
    when(scorer.delta(
            any(SpecUpKrakenScore.Context.class),
            argThat(
                (SpecUpKrakenScore.Edit edit) ->
                    edit instanceof SpecUpKrakenScore.Stone stone
                        && stone.levels().equals(Map.of("원한", 3, "아드레날린", 2)))))
        .thenReturn(first);
    when(scorer.delta(
            any(SpecUpKrakenScore.Context.class),
            argThat(
                (SpecUpKrakenScore.Edit edit) ->
                    edit instanceof SpecUpKrakenScore.Stone stone
                        && stone.levels().equals(Map.of("원한", 2, "아드레날린", 3)))))
        .thenReturn(flipped);
    return scorer;
  }

  @Test
  void 점수가_같으면_앞_배치이고_더_오르면_그_배치다() {
    var context = new SpecUpKrakenScore.Context(null, 100.0, null, false);
    var names = List.of("원한", "아드레날린");
    // 같은 점수(전투력만 다른 두 배치): 앞 배치(점수 입력 순서대로 높은 레벨 먼저).
    assertThat(
            SpecUpScoreCandidates.orientation(scorer(1.5, 1.5), context, names, NINE_SEVEN)
                .levels())
        .isEqualTo(Map.of("원한", 3, "아드레날린", 2));
    assertThat(
            SpecUpScoreCandidates.orientation(scorer(1.0, 1.5), context, names, NINE_SEVEN)
                .levels())
        .isEqualTo(Map.of("원한", 2, "아드레날린", 3));
    assertThat(
            SpecUpScoreCandidates.orientation(scorer(1.5, 1.0), context, names, NINE_SEVEN)
                .levels())
        .isEqualTo(Map.of("원한", 3, "아드레날린", 2));
  }
}
