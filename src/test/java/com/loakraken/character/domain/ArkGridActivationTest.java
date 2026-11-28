package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 공식 가이드 '아크 그리드' 활성화 규칙: 코어에서 가까운 슬롯부터 차례로, 모자라거나 비면 그 슬롯부터 뒤는 모두 꺼짐. */
class ArkGridActivationTest {
  @Test
  void 의지력이_다_맞으면_모두_켜진다() {
    assertThat(ArkGridActivation.active(17, Map.of(0, 3, 1, 4, 2, 3, 3, 7)))
        .containsExactly(0, 1, 2, 3);
  }

  @Test
  void 모자란_슬롯부터_그_뒤는_모두_꺼진다() {
    // 3 + 4 + 3 = 10, 남은 7 < 8: 젬 3 꺼짐.
    assertThat(ArkGridActivation.active(17, Map.of(0, 3, 1, 4, 2, 3, 3, 8)))
        .containsExactly(0, 1, 2);
    // 앞 슬롯이 모자라면 뒤 슬롯이 맞아도 꺼진다(하나씩 골라 켜지 않는다).
    assertThat(ArkGridActivation.active(10, Map.of(0, 3, 1, 9, 2, 1))).containsExactly(0);
  }

  @Test
  void 빈_슬롯부터_그_뒤는_꺼진다() {
    assertThat(ArkGridActivation.active(17, Map.of(0, 3, 2, 3))).containsExactly(0);
    assertThat(ArkGridActivation.active(17, Map.of())).isEmpty();
  }

  @Test
  void 딱_맞으면_켜진다() {
    assertThat(ArkGridActivation.active(8, Map.of(0, 8))).containsExactly(0);
    assertThat(ArkGridActivation.active(7, Map.of(0, 8))).isEmpty();
  }
}
