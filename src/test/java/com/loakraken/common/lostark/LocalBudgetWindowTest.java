package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalBudgetWindowTest {
  @Test
  void 되돌리기는_돌려받은_그_기록만_지운다() {
    var window = new LocalBudgetWindow();
    var first = window.tryAdmit(0, 2);
    var second = window.tryAdmit(30_000, 2);
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(window.tryAdmit(31_000, 2)).isNull();
    // 먼저 남긴 기록을 되돌리면 나중 기록(30초)은 남는다 — 60초가 지나 첫 기록 시각이 창 밖이어도 61초에는 한 칸만 빈다.
    window.undo(first);
    assertThat(window.available(61_000, 1)).isFalse();
    assertThat(window.available(61_000, 2)).isTrue();
    // 이미 창 밖으로 지워진 기록을 되돌려도 다른 기록은 그대로다.
    window.undo(first);
    assertThat(window.available(89_000, 1)).isFalse();
    assertThat(window.available(90_000, 1)).isTrue();
  }

  @Test
  void 예산이_0_이하면_허가하지_않는다() {
    var window = new LocalBudgetWindow();
    assertThat(window.tryAdmit(0, 0)).isNull();
    assertThat(window.available(0, 0)).isFalse();
    window.undo(null);
  }
}
