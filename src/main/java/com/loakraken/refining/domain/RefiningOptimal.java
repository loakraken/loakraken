package com.loakraken.refining.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 회차별 최적 전략(#284). 한 단계에서 시도마다 숨결 개수(0~최대)와 책 사용 여부를 따로 골라 기대 골드 비용이 가장 작아지게 한다(단계 전체에 한 조합을 고정하는
 * 것보다 같거나 싸다). 게임도 숨결을 일부만 넣을 수 있다(계산 검증 #287: 마지막 회차는 숨결 몇 개만 쓰는 것이 싸다).
 *
 * <p>상태는 (실패 횟수, 장인의 기운)이다. 실패 보너스는 10회에서 멈추므로 실패 횟수는 0~10이면 충분하고, 장인의 기운은 {@link RefiningPlan}과 같은
 * 0.001%p 단위(0~100000) 정수다. 기운이 100%면 그 시도는 숨결·책 없이 확정 성공이다. 실패하면 다음 상태가 하나로 정해지므로, 최적 정책의 실패
 * 경로(회차별 선택)도 하나다.
 *
 * <p>메모리(PM-백엔드 #287 P1): 기대값은 현재·다음 실패 행 두 줄만 두고, 회차별 선택은 byte 표(실패 11 × 기운 100000, 약 1.1MB)로 저장해
 * 경로를 복원한다.
 */
public final class RefiningOptimal {
  private static final int FULL = 100_000;
  private static final int BONUS_FAILURES = RefiningPlan.MAX_BONUS_FAILURES;

  /**
   * 회차별 최적의 기대 골드(성장 비용 제외)와 실패 경로에서 숨결·책을 쓰는 회차가 있는지. path는 실패가 이어질 때 회차별 선택(예: "숨결 20+책", "숨결 4",
   * "기본")이다.
   */
  public record Result(
      double expectedGold, boolean usesBreath, boolean usesBook, List<String> path) {}

  private record Choice(int breaths, boolean book, double bonusBp, double extraGold) {
    String label() {
      if (breaths == 0) return book ? "책" : "기본";
      return "숨결 " + breaths + (book ? "+책" : "");
    }
  }

  private record Buffers(double[] rowA, double[] rowB, byte[][] picks) {}

  private static final ThreadLocal<Buffers> BUFFERS =
      ThreadLocal.withInitial(
          () ->
              new Buffers(
                  new double[FULL + 1], new double[FULL + 1], new byte[BONUS_FAILURES + 1][FULL]));

  private RefiningOptimal() {}

  /**
   * @param goldRate 성장 지원이면 0.8, 아니면 1
   * @param prices 재료 1개당 골드. 기본 재료·파편 값이 없으면 null을 돌려준다. 숨결·책 값이 없으면 그 선택지를 쓰지 않는다
   */
  public static Result calculate(
      RefiningRules.Stage rule,
      double goldRate,
      Map<String, ? extends Number> prices,
      boolean breathAllowed,
      boolean bookAllowed) {
    double base = rule.gold() * goldRate;
    for (var material : rule.materials()) {
      var price = prices.get(material.name());
      if (price == null) {
        if (material.count() == 0) continue;
        return null;
      }
      base += material.count() * price.doubleValue();
    }
    if (rule.shard() > 0) {
      var price = prices.get(RefiningPlan.SHARD);
      if (price == null) return null;
      base += rule.shard() * price.doubleValue();
    }
    var breath = breathAllowed ? rule.breath() : null;
    var breathPrice = breath == null ? null : prices.get(breath.name());
    var book = bookAllowed ? rule.book() : null;
    var bookPrice = book == null ? null : prices.get(book.name());
    int maxBreaths = breathPrice == null ? 0 : breath.maxCount();
    var choices = new ArrayList<Choice>();
    for (int breaths = 0; breaths <= maxBreaths; breaths++)
      for (boolean useBook :
          bookPrice == null ? new boolean[] {false} : new boolean[] {false, true})
        choices.add(
            new Choice(
                breaths,
                useBook,
                (breaths == 0 ? 0 : (double) breath.rateBp() * breaths)
                    + (useBook ? book.rateBp() : 0),
                (breaths == 0 ? 0 : breaths * breathPrice.doubleValue())
                    + (useBook ? bookPrice.doubleValue() : 0)));
    int size = choices.size();
    // 실패 n회마다 선택지별 실패 확률·비용·기운 증가는 기운과 무관하다.
    var fail = new double[BONUS_FAILURES + 1][size];
    var cost = new double[size];
    var increment = new int[BONUS_FAILURES + 1][size];
    for (int c = 0; c < size; c++) cost[c] = base + choices.get(c).extraGold();
    for (int n = 0; n <= BONUS_FAILURES; n++)
      for (int c = 0; c < size; c++) {
        double rateBp = rule.baseRateBp() * (1 + 0.1 * n) + choices.get(c).bonusBp();
        double p = Math.min(10_000, rateBp) / 10_000;
        fail[n][c] = 1 - p;
        increment[n][c] = (int) Math.round(p * RefiningPlan.ARTISAN_RATE * FULL);
      }
    // 두 줄과 선택표는 스레드마다 한 번 만들고 다시 쓴다(PM-백엔드 #287 P1). 매번 모든 칸을 새로 채운다.
    var buffers = BUFFERS.get();
    var picks = buffers.picks();
    var rowA = buffers.rowA();
    var rowB = buffers.rowB();
    double[] next = null;
    double[] current = rowA;
    for (int n = BONUS_FAILURES; n >= 0; n--) {
      current = next == rowA ? rowB : rowA;
      current[FULL] = base;
      var failN = fail[n];
      var incN = increment[n];
      var pickN = picks[n];
      if (n == BONUS_FAILURES) {
        // 실패 10회 이상은 같은 행을 다시 쓴다(실패 보너스가 멈춤). 높은 기운부터 채운다.
        for (int energy = FULL - 1; energy >= 0; energy--) {
          double best = Double.MAX_VALUE;
          int bestChoice = 0;
          for (int c = 0; c < size; c++) {
            double value = cost[c];
            if (failN[c] > 0) value += failN[c] * current[Math.min(FULL, energy + incN[c])];
            if (value < best) {
              best = value;
              bestChoice = c;
            }
          }
          current[energy] = best;
          pickN[energy] = (byte) bestChoice;
        }
      } else {
        // 다음 행이 따로 있으니 선택지 바깥·기운 안쪽으로 돌아 메모리를 차례로 읽는다.
        java.util.Arrays.fill(current, 0, FULL, Double.MAX_VALUE);
        for (int c = 0; c < size; c++) {
          double costC = cost[c];
          double failC = failN[c];
          int incC = incN[c];
          byte pick = (byte) c;
          int split = Math.max(0, Math.min(FULL, FULL - incC));
          if (failC > 0) {
            for (int energy = 0; energy < split; energy++) {
              double value = costC + failC * next[energy + incC];
              if (value < current[energy]) {
                current[energy] = value;
                pickN[energy] = pick;
              }
            }
            double atFull = costC + failC * next[FULL];
            for (int energy = split; energy < FULL; energy++)
              if (atFull < current[energy]) {
                current[energy] = atFull;
                pickN[energy] = pick;
              }
          } else {
            for (int energy = 0; energy < FULL; energy++)
              if (costC < current[energy]) {
                current[energy] = costC;
                pickN[energy] = pick;
              }
          }
        }
      }
      next = current;
    }
    // 실패 경로를 따라 회차별 선택을 다시 찾는다.
    boolean usesBreath = false;
    boolean usesBook = false;
    var path = new ArrayList<String>();
    int failures = 0;
    int energy = 0;
    while (energy < FULL && path.size() < 10_000) {
      int n = Math.min(failures, BONUS_FAILURES);
      int c = picks[n][energy];
      var choice = choices.get(c);
      path.add(choice.label());
      usesBreath |= choice.breaths() > 0;
      usesBook |= choice.book();
      if (fail[n][c] <= 0) break;
      energy += increment[n][c];
      failures++;
    }
    return new Result(current[0], usesBreath, usesBook, List.copyOf(path));
  }
}
