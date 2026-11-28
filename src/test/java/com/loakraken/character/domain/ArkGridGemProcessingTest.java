package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 젬 가공 정확 풀이(M49 2단계, 계산 검증 명세 §5 검증 1~3). 사례는
 * src/test/resources/verification/arkgrid-gem/ref-g-2026-10-09.json: computed(10-09 표본 스크립트)
 * ±0.3%p, expected(비교 기준, 정수 %) ±0.5%p. 옵션 번호 0 의지력 효율 · 1 질서/혼돈 포인트 · 2 효과1 · 3 효과2, 추가 비율 0, 초기화
 * 없음.
 */
class ArkGridGemProcessingTest {
  private static ArkGridGemProcessing model;

  @BeforeAll
  static void 규칙() {
    model = new ArkGridGemProcessing(ArkGridGemProcessing.rules());
  }

  private static double pct(double probability) {
    return probability * 100;
  }

  @Test
  void 분배_직후_영웅_보인_4개가_모두_1이면_16은_22퍼센트이고_19는_2퍼센트다() {
    var shown = List.of("PLUS_1:0", "PLUS_1:1", "PLUS_1:2", "PLUS_1:3");
    var p16 = model.shownSuccess("HERO", 16, new int[] {1, 1, 1, 1}, 9, 2, 0, false, shown);
    var p19 = model.shownSuccess("HERO", 19, new int[] {1, 1, 1, 1}, 9, 2, 0, false, shown);
    assertThat(pct(p16)).isCloseTo(22.27, within(0.3)).isCloseTo(22, within(0.5));
    assertThat(pct(p19)).isCloseTo(1.69, within(0.3)).isCloseTo(2, within(0.5));
  }

  @Test
  void 사례1_모두_3_남은_가공_5() {
    var shown = List.of("PLUS_1:0", "PLUS_1:1", "PLUS_1:2", "PLUS_1:3");
    var p16 = model.shownSuccess("HERO", 16, new int[] {3, 3, 3, 3}, 5, 1, 0, true, shown);
    var p19 = model.shownSuccess("HERO", 19, new int[] {3, 3, 3, 3}, 5, 1, 0, true, shown);
    assertThat(pct(p16)).isCloseTo(65.78, within(0.3)).isCloseTo(66, within(0.5));
    assertThat(pct(p19)).isCloseTo(3.23, within(0.3)).isCloseTo(3, within(0.5));
  }

  @Test
  void 사례2_남은_가공_4_보기_없음() {
    var shown = List.of("PLUS_2:1", "PLUS_2:3", "MINUS_1:0", "KEEP");
    var p16 = model.shownSuccess("HERO", 16, new int[] {4, 3, 4, 3}, 4, 0, 0, true, shown);
    var p19 = model.shownSuccess("HERO", 19, new int[] {4, 3, 4, 3}, 4, 0, 0, true, shown);
    assertThat(pct(p16)).isCloseTo(76.34, within(0.3)).isCloseTo(76, within(0.5));
    assertThat(pct(p19)).isCloseTo(8.78, within(0.3)).isCloseTo(9, within(0.5));
  }

  /** 정확 풀이 77.71(계산 검증의 독립 정확 풀이와 같음, 11-22) — 10-09 값 78.18과의 차이는 그 스크립트의 표본 잡음. 비교 기준 78 ±0.5. */
  @Test
  void 사례4_남은_가공_2() {
    var shown = List.of("PLUS_1:1", "PLUS_1:3", "MINUS_1:0", "MINUS_1:2");
    var p16 = model.shownSuccess("HERO", 16, new int[] {4, 4, 3, 4}, 2, 1, 0, true, shown);
    assertThat(pct(p16)).isCloseTo(77.71, within(0.01)).isCloseTo(78, within(0.5));
  }

  /**
   * 초기화는 가공이 1회 이상 남았을 때만(공식 확률 정보 2025-08-20): 분배 직후 영웅(보인 4개 모두 +1) 16은 38.56%. 10-09의 42.8%는 마지막
   * 가공 뒤에도 초기화를 허용한 비공식 규칙 값이었다(계산 검증 독립 정확 풀이, 11-22).
   */
  @Test
  void 분배_직후_영웅_보인_4개가_모두_1이고_초기화를_쓰면_16은_38_56퍼센트다() {
    var shown = List.of("PLUS_1:0", "PLUS_1:1", "PLUS_1:2", "PLUS_1:3");
    var on = model.shownSuccess("HERO", 16, new int[] {1, 1, 1, 1}, 9, 2, 0, false, true, shown);
    assertThat(pct(on)).isCloseTo(38.56, within(0.01));
  }

  @Test
  void 처음부터_계산한_표의_끝_분포의_합은_1이고_초기화를_쓰면_확률이_오른다() {
    long started = System.nanoTime();
    var on = model.table("HERO", true, 16);
    long onMs = (System.nanoTime() - started) / 1_000_000;
    started = System.nanoTime();
    var off = model.table("HERO", false, 16);
    long offMs = (System.nanoTime() - started) / 1_000_000;
    System.out.printf(
        "GEMDP HERO T16 reset=on P=%.4f%% gold=%.1f tickets=%.4f ms=%d | reset=off P=%.4f%% gold=%.1f ms=%d%n",
        pct(on.successProbability()),
        on.expectedGold(),
        on.expectedTickets(),
        onMs,
        pct(off.successProbability()),
        off.expectedGold(),
        offMs);
    // 분배 직후(보이기 전) 16: 끔 25.48%, 켬 40.98%(계산 검증 독립 정확 풀이와 같음, 11-22).
    assertThat(pct(off.successProbability())).isCloseTo(25.48, within(0.01));
    assertThat(pct(on.successProbability())).isCloseTo(40.98, within(0.01));
    assertThat(sum(on.terminal())).isCloseTo(1.0, within(1e-9));
    assertThat(sum(off.terminal())).isCloseTo(1.0, within(1e-9));
    assertThat(off.expectedTickets()).isZero();
    assertThat(on.expectedTickets()).isBetween(0.0, 1.0);
    assertThat(on.successProbability()).isGreaterThan(off.successProbability());
  }

  /** 리소스 표(64개, ArkGridGemTableGeneratorTest가 만듦)는 규칙 버전이 같고 합이 1이며, 하나를 다시 풀어도 같다. */
  @Test
  void 미리_계산한_정책_표는_다시_풀어도_같다() {
    var tables = ArkGridGemTables.load();
    assertThat(tables.ruleVersion()).isEqualTo(ArkGridGemProcessing.rules().ruleVersion());
    assertThat(tables.tables()).hasSize(64);
    var fresh = model.table("HERO", false, 16);
    assertThat(tables.find("HERO", false, 16))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.successProbability())
                  .isCloseTo(fresh.successProbability(), within(1e-9));
              assertThat(stored.expectedGold()).isCloseTo(fresh.expectedGold(), within(1e-3));
              assertThat(stored.terminal()).hasSize(fresh.terminal().length);
              for (int t = 0; t < fresh.terminal().length; t++)
                assertThat(stored.terminal()[t]).isCloseTo(fresh.terminal()[t], within(1e-12));
            });
    assertThat(tables.find("RARE", true, 14))
        .hasValueSatisfying(rare -> assertThat(sum(rare.terminal())).isCloseTo(1.0, within(1e-9)));
  }

  @Test
  void 멀티셋_순서_수는_서로_다른_순서만_센다() {
    assertThat(model.tupleCount()).isEqualTo(70);
    assertThat(model.permutations(model.index(new int[] {1, 1, 1, 1}))).isEqualTo(1);
    assertThat(model.permutations(model.index(new int[] {1, 2, 3, 4}))).isEqualTo(24);
    assertThat(model.permutations(model.index(new int[] {2, 2, 5, 5}))).isEqualTo(6);
    assertThat(model.permutations(model.index(new int[] {3, 1, 3, 3}))).isEqualTo(4);
    int total = 0;
    for (int i = 0; i < model.tupleCount(); i++) total += model.permutations(i);
    assertThat(total).isEqualTo(625);
  }

  private static double sum(double[] values) {
    double total = 0;
    for (double value : values) total += value;
    return total;
  }
}
