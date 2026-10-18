package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 보석 귀속 판정(#560): 이름의 '(귀속)' 또는 공식 툴팁 거래 줄. */
class GemTradeTest {
  @Test
  void 이름이나_거래_줄_가운데_하나라도_귀속이면_귀속이다() {
    assertThat(GemTrade.bound(gem("8레벨 겁화의 보석 (귀속)", List.of()))).isTrue();
    // 이름에 표시가 없어도 거래 줄이 귀속이면 귀속(이번 버그).
    assertThat(GemTrade.bound(gem("8레벨 겁화의 보석", List.of("캐릭터 귀속됨")))).isTrue();
    assertThat(GemTrade.bound(gem("8레벨 겁화의 보석", List.of("거래 불가")))).isTrue();
    assertThat(GemTrade.bound(gem("8레벨 겁화의 보석", List.of("원정대 귀속됨", "거래 불가")))).isTrue();
    assertThat(GemTrade.bound(gem("8레벨 겁화의 보석", List.of("거래불가")))).isTrue();
  }

  @Test
  void 이름과_거래_줄_모두_귀속이_아니면_팔_수_있다() {
    assertThat(GemTrade.bound(gem("8레벨 겁화의 보석", List.of("거래가능")))).isFalse();
    assertThat(GemTrade.bound(gem("9레벨 작열의 보석", List.of("거래 3회 가능")))).isFalse();
    // 거래 줄을 못 읽었으면 지금처럼 이름만 본다.
    assertThat(GemTrade.bound(gem("10레벨 겁화의 보석", List.of()))).isFalse();
    assertThat(GemTrade.bound(null)).isFalse();
  }

  @Test
  void 공식_툴팁_거래_줄_표본과_공백_차이를_읽고_조건_문구는_귀속으로_보지_않는다() {
    // 공식 armory 보석 툴팁에서 본 줄: 귀속 보석 '캐릭터 귀속됨'·'거래 불가', 거래 가능 보석 '거래 3회 가능'·'거래가능'.
    assertThat(GemTrade.bound(gem("10레벨 겁화의 보석", List.of("캐릭터 귀속됨", "거래 불가")))).isTrue();
    assertThat(GemTrade.bound(gem("10레벨 겁화의 보석", List.of("거래 3회 가능")))).isFalse();
    // 탭·줄 바꿈 없는 공백(U+00A0)이 섞여도 읽는다.
    assertThat(GemTrade.bound(gem("10레벨 겁화의 보석", List.of("캐릭터\u00a0귀속됨")))).isTrue();
    assertThat(GemTrade.bound(gem("10레벨 겁화의 보석", List.of("거래\t불가")))).isTrue();
    // 조건 문구는 지금 귀속이라는 뜻이 아니다.
    assertThat(GemTrade.bound(gem("10레벨 겁화의 보석", List.of("귀속 시 거래 불가")))).isFalse();
    assertThat(GemTrade.bound(gem("10레벨 겁화의 보석", List.of("장착 시 캐릭터 귀속됨")))).isFalse();
  }

  private static CharacterLoadout.Gem gem(String name, List<String> tradeLines) {
    return new CharacterLoadout.Gem(0, name, null, 8, "고대", List.of(), null, null, 4, tradeLines);
  }
}
