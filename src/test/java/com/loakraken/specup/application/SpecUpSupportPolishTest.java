package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 서포터 연마 후보 모양은 비교 기준 B의 서포터 장신구 후보와 같다(원본 모듈 확인). */
class SpecUpSupportPolishTest {
  private static JsonNode lines(String... lines) {
    var array = JsonNodeFactory.instance.arrayNode();
    for (var line : lines) array.add(line);
    return array;
  }

  private static List<String> codes(List<SpecUpSupportPolish.Next> candidates) {
    return candidates.stream().map(SpecUpSupportPolish.Next::code).toList();
  }

  @Test
  void 주옵션_둘이면_더_높은_등급만_보고_상이_둘_안되면_고정값_공격력_줄은_목표에서_뺀다() {
    var next =
        SpecUpSupportPolish.candidates(
            "necklace", lines("낙인력 +4.80%", "세레나데, 신앙, 조화 게이지 획득량 +6.00%", "공격력 +80"));
    assertThat(codes(next)).containsExactly("SHHN");
    assertThat(next.get(0).lines()).containsExactly("낙인력 +8.00%", "세레나데, 신앙, 조화 게이지 획득량 +6.00%");
  }

  @Test
  void 주옵션이_하나면_부위_추가_옵션을_붙이고_같은_계열은_붙이지_않는다() {
    // 귀걸이: 최대 생명력(서포터 주옵션) 하나 + 딜러 공격력 % + 고정값 무기 공격력(상 둘이 아니라 빠짐)
    var next =
        SpecUpSupportPolish.candidates(
            "earing1", lines("최대 생명력 +1300", "공격력 +0.40%", "무기 공격력 +195"));
    // 등급 올리기 2(중·상) + (지금·중·상 3) × 최대 마나 3(+6은 따로 계열) = 11
    assertThat(codes(next))
        .containsExactly(
            "SMLN", "SHLN", "SLLNAPL", "SLLNAPM", "SLLNAPH", "SMLNAPL", "SMLNAPM", "SMLNAPH",
            "SHLNAPL", "SHLNAPM", "SHLNAPH");
    assertThat(next.get(2).lines()).containsExactly("최대 생명력 +1300", "공격력 +0.40%", "최대 마나 +6");
  }

  @Test
  void 후보_id는_줄_상세_id_모양에_맞는다() {
    // 코드 리뷰 10-25: 붙인 옵션 구분자 '+'가 줄 상세 id 모양에 없어 상세가 400이었다. 목록 id 그대로 상세를 부를 수 있어야 한다.
    var next =
        SpecUpSupportPolish.candidates(
            "earing1", lines("최대 생명력 +1300", "공격력 +0.40%", "무기 공격력 +195"));
    assertThat(next).isNotEmpty();
    for (var candidate : next)
      assertThat("POLISH:7:" + candidate.code())
          .matches(com.loakraken.specup.presentation.SpecUpDetailController.CANDIDATE_ID);
  }

  @Test
  void 반지에는_처음_이름이_다른_아군_강화_옵션을_붙이지_않는다() {
    // 아군 공격력 강화 하나뿐: B는 처음 이름(아군 공격력 강화 …)으로 견주어 반지 추가 목록과 맞지 않는다 → 등급 올리기만
    var next =
        SpecUpSupportPolish.candidates(
            "ring1", lines("아군 공격력 강화 효과 +1.35%", "치명타 피해 +1.10%", "공격력 +80"));
    assertThat(codes(next)).containsExactly("SMLN", "SHLN");
  }

  @Test
  void 서포터_상이_둘이면_고정값_무기_공격력을_남기고_더_높은_등급으로도_바꿔_본다() {
    var next =
        SpecUpSupportPolish.candidates(
            "ring2", lines("아군 공격력 강화 효과 +5.00%", "아군 피해량 강화 효과 +7.50%", "무기 공격력 +480"));
    assertThat(codes(next)).containsExactly("SHHH");
    assertThat(next.get(0).lines())
        .containsExactly("아군 공격력 강화 효과 +5.00%", "아군 피해량 강화 효과 +7.50%", "무기 공격력 +960");
  }

  @Test
  void 최대_마나_6은_유물_고대_공통_줄이라_더_올릴_등급이_없다() {
    var next =
        SpecUpSupportPolish.candidates("earing2", lines("최대 마나 +6", "최대 생명력 +3250", "공격력 +0.95%"));
    // 최대 마나 +6은 그대로, 최대 생명력 중 → 상만. 주옵션이 둘이라 붙이지 않음.
    assertThat(codes(next)).containsExactly("SLHM");
  }
}
