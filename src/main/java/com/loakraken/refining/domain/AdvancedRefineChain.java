package com.loakraken.refining.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상급 재련(T4, 1~40단계) 기대 턴 수(계산 검증 명세 2026-11-22). 10단계 구간마다 담금질 상한에서 경험치·구슬·무료 턴이 모두 사라지므로 구간은 서로
 * 독립이다. 상태(구간 안 경험치 e, 구슬 m ∈ {0,2,4,6}, 테메르 무료 턴, 나베르 강화 턴)는 매 턴 e가 10 이상 늘어 순환이 없으므로, 기억해 두는 재귀로
 * 정확히 푼다.
 *
 * <ul>
 *   <li>일반 턴(유료·무료): 결과(성공 10 · 대성공 20 · 대성공x2 40)를 선택 재료 조합 K의 확률로 뽑고, 구슬 +2(최대 6), 무료 턴은 끝난다.
 *   <li>구슬 6이면 다음 턴은 선조 턴(나베르 뒤면 강화 선조 턴): 결과 x를 같은 방식으로 뽑고 선조를 뽑는다. 선조 턴은 구슬을 더하지 않는다.
 *   <li>K = 숨결 + 2 × 책(0 없음 · 1 숨결 · 2 책 · 3 둘 다). 정책은 턴 종류(일반·선조·강화 선조)마다 K 하나.
 * </ul>
 */
public final class AdvancedRefineChain {
  /** 결과 경험치(성공·대성공·대성공x2). 100 = 1단계. */
  static final int[] OUTCOME_EXP = {10, 20, 40};

  /** 구간 끝(경험치). 10단계 = 1000. */
  public static final int BAND_EXP = 1000;

  private AdvancedRefineChain() {}

  /** 선조. 효과는 명세 §2(일반 선조 턴과 강화 선조 턴의 값이 다르다). */
  public enum Ancestor {
    GALATUR,
    GELAR,
    KUHUMBAR,
    TEMER,
    NABER,
    EBER
  }

  /**
   * 확률 규칙. outcome[K] = 성공·대성공·대성공x2 확률(합 1), ancestorsLow = 1~20단계 선조 표, ancestorsHigh = 21~40단계,
   * enhanced = 나베르 뒤 강화 선조 표.
   */
  public record Odds(
      double[][] outcome,
      Map<Ancestor, Double> ancestorsLow,
      Map<Ancestor, Double> ancestorsHigh,
      Map<Ancestor, Double> enhanced) {}

  /** 정책: 턴 종류별 선택 재료 조합 K(0~3). */
  public record Policy(int normal, int ancestor, int enhanced) {
    public Policy {
      for (int k : new int[] {normal, ancestor, enhanced})
        if (k < 0 || k > 3) throw new IllegalArgumentException("K는 0~3입니다.");
    }

    /** 1~20단계 구간은 강화 선조 턴이 없어 enhanced가 의미 없다(0으로 둔다). */
    public static List<Policy> family(boolean high) {
      var result = new java.util.ArrayList<Policy>();
      for (int n = 0; n < 4; n++)
        for (int a = 0; a < 4; a++)
          if (high) for (int e = 0; e < 4; e++) result.add(new Policy(n, a, e));
          else result.add(new Policy(n, a, 0));
      return List.copyOf(result);
    }
  }

  /** 기대 턴 수(유료 일반 · 무료 일반 · 선조 · 강화 선조). */
  public record Turns(double paid, double free, double ancestor, double enhanced) {
    public double total() {
      return paid + free + ancestor + enhanced;
    }

    Turns plus(Turns other, double weight) {
      return new Turns(
          paid + weight * other.paid,
          free + weight * other.free,
          ancestor + weight * other.ancestor,
          enhanced + weight * other.enhanced);
    }
  }

  /**
   * 구간 시작 단계(0·10·20·30)에서 경험치 startExp(구간 안, 0~990)로 시작해 targetExp(≤ 1000)에 닿을 때까지의 기대 턴 수. 구슬 0 ·
   * 무료 턴 없음 · 강화 없음에서 시작한다(공식 API는 단계만 주므로 가정).
   */
  public static Turns expected(
      Odds odds, int bandStart, int startExp, int targetExp, Policy policy) {
    if (bandStart % 10 != 0 || bandStart < 0 || bandStart > 30)
      throw new IllegalArgumentException("구간 시작은 0·10·20·30입니다.");
    if (targetExp <= 0 || targetExp > BAND_EXP)
      throw new IllegalArgumentException("목표 경험치는 1~1000입니다.");
    if (startExp < 0 || startExp >= targetExp) return new Turns(0, 0, 0, 0);
    var ancestors = bandStart < 20 ? odds.ancestorsLow() : odds.ancestorsHigh();
    return new Solver(odds, ancestors, targetExp, policy).solve(startExp, 0, false, false);
  }

  private static final class Solver {
    private final Odds odds;
    private final Map<Ancestor, Double> ancestors;
    private final int target;
    private final Policy policy;
    private final Map<Integer, Turns> memo = new HashMap<>();

    Solver(Odds odds, Map<Ancestor, Double> ancestors, int target, Policy policy) {
      this.odds = odds;
      this.ancestors = ancestors;
      this.target = target;
      this.policy = policy;
    }

    Turns solve(int e, int m, boolean free, boolean enh) {
      if (e >= target) return new Turns(0, 0, 0, 0);
      int key = ((e * 8 + m) * 2 + (free ? 1 : 0)) * 2 + (enh ? 1 : 0);
      var cached = memo.get(key);
      if (cached != null) return cached;
      Turns result;
      if (m >= 6) result = enh ? enhancedTurn(e) : ancestorTurn(e);
      else result = normalTurn(e, m, free);
      memo.put(key, result);
      return result;
    }

    private Turns normalTurn(int e, int m, boolean free) {
      var base = free ? new Turns(0, 1, 0, 0) : new Turns(1, 0, 0, 0);
      var p = odds.outcome()[policy.normal()];
      int marbles = Math.min(6, m + 2);
      var result = base;
      for (int i = 0; i < 3; i++) {
        if (p[i] == 0) continue;
        result = result.plus(solve(e + OUTCOME_EXP[i], marbles, false, false), p[i]);
      }
      return result;
    }

    private Turns ancestorTurn(int e) {
      var result = new Turns(0, 0, 1, 0);
      var p = odds.outcome()[policy.ancestor()];
      for (int i = 0; i < 3; i++) {
        if (p[i] == 0) continue;
        int x = OUTCOME_EXP[i];
        for (var entry : ancestors.entrySet()) {
          double w = p[i] * entry.getValue();
          if (w == 0) continue;
          var next =
              switch (entry.getKey()) {
                case GALATUR -> solve(e + 5 * x, 0, false, false);
                case GELAR -> solve(e + 3 * x, 0, false, false);
                case KUHUMBAR -> solve(e + x + 30, 6, false, false);
                case TEMER -> solve(e + x + 10, 0, true, false);
                case NABER -> solve(e + x, 6, false, true);
                case EBER -> solve((e + x) / 100 * 100 + 100, 0, false, false);
              };
          result = result.plus(next, w);
        }
      }
      return result;
    }

    private Turns enhancedTurn(int e) {
      var result = new Turns(0, 0, 0, 1);
      var p = odds.outcome()[policy.enhanced()];
      for (int i = 0; i < 3; i++) {
        if (p[i] == 0) continue;
        int x = OUTCOME_EXP[i];
        for (var entry : odds.enhanced().entrySet()) {
          double w = p[i] * entry.getValue();
          if (w == 0) continue;
          var next =
              switch (entry.getKey()) {
                case GALATUR -> solve(e + 7 * x, 0, false, false);
                case GELAR -> solve(e + 5 * x, 0, false, false);
                case KUHUMBAR -> solve(e + x + 80, 6, false, false);
                case TEMER -> solve(e + x + 30, 0, true, false);
                case NABER -> solve(e + x, 6, false, true);
                case EBER -> solve((e + x) / 100 * 100 + 200, 0, false, false);
              };
          result = result.plus(next, w);
        }
      }
      return result;
    }
  }
}
