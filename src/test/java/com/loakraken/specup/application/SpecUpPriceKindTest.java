package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 보석 비용의 시세 종류(P0 #455 B1): 광휘는 그 칸 효과와 같은 종류, 효과를 못 읽으면 null. */
class SpecUpPriceKindTest {
  @Test
  void 광휘는_피해_증가면_겁화_재사용_대기시간_감소면_작열이다() {
    assertThat(SpecUpService.priceKind("광휘", List.of("피해 44.00% 증가"))).isEqualTo("겁화");
    assertThat(SpecUpService.priceKind("광휘", List.of("재사용 대기시간 24.00% 감소"))).isEqualTo("작열");
  }

  @Test
  void 겁화_작열은_그대로이고_광휘_효과를_못_읽으면_null이다() {
    assertThat(SpecUpService.priceKind("겁화", null)).isEqualTo("겁화");
    assertThat(SpecUpService.priceKind("작열", List.of())).isEqualTo("작열");
    assertThat(SpecUpService.priceKind("광휘", null)).isNull();
    assertThat(SpecUpService.priceKind("광휘", List.of("알 수 없는 효과"))).isNull();
  }
}
