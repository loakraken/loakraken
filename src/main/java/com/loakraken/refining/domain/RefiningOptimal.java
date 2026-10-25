package com.loakraken.refining.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 회차별 최적 전략(#284). 한 단계에서 시도마다 숨결 개수(0~최대)와 책 사용 여부를 따로 골라 기대 골드 비용이 가장 작아지게 한다(단계 전체에 한 조합을 고정하는
 * 것보다 같거나 싸다). 게임도 숨결을 일부만 넣을 수 있다(계산 검증 #287: 마지막 회차는 숨결 몇 개만 쓰는 것이 싸다).
 *
 * <p>상태는 (실패 횟수, 장인의 기운)이다. 실패 보너스는 10회에서 멈추므로 실패 횟수는 0~10이면 충분하고, 장인의 기운은 {@link RefiningPlan}과 같은
 * 0.001%p 단위(0~100000) 정수다(쌓이는 양은 게임처럼 0.01%p에서 버림). 기운이 100%면 그 시도는 숨결·책 없이 확정 성공이다. 실패하면 다음 상태가
 * 하나로 정해지므로, 최적 정책의 실패 경로(회차별 선택)도 하나다.
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
      double expectedGold,
      boolean usesBreath,
      boolean usesBook,
      List<String> path,
      double expectedAttempts,
      double expectedBreaths,
      double expectedBooks,
      int firstRateBp,
      double expectedSecondBreaths) {
    /**
     * 기대 수량 없이(캐시 테스트 등). expectedAttempts·expectedBreaths·expectedBooks는 최적 정책의 기대 시도 수·숨결 개수·책
     * 권수로, 기대 골드 = 시도 × 기본 비용 + 숨결 × 숨결 값 + 책 × 책 값이다. firstRateBp는 첫 시도(기운 0, 실패 0)에 고른 선택의 성공
     * 확률(만분율)이다.
     */
    public Result(double expectedGold, boolean usesBreath, boolean usesBook, List<String> path) {
      this(expectedGold, usesBreath, usesBook, path, Double.NaN, Double.NaN, Double.NaN, 0, 0);
    }
  }

  /** second: 두 번째(비싼) 숨결 개수 — 팔 보호구처럼 숨결이 두 가지일 때만, 아니면 0. */
  private record Choice(int breaths, boolean book, double bonusBp, double extraGold, int second) {
    Choice(int breaths, boolean book, double bonusBp, double extraGold) {
      this(breaths, book, bonusBp, extraGold, 0);
    }

    String label() {
      if (breaths == 0) return book ? "책" : "기본";
      return "숨결 " + breaths + (book ? "+책" : "");
    }
  }

  private record Buffers(double[] rowA, double[] rowB, byte[][] picks) {}

  /**
   * 계산 버퍼 풀(#292). 요청이 가상 스레드라 ThreadLocal은 요청마다 새로 생긴다. 다 쓴 버퍼(약 2.7MB)를 최대 4벌 보관해 요청 사이에 다시 쓴다.
   */
  private static final int POOL_SIZE = 4;

  private static final java.util.concurrent.ArrayBlockingQueue<Buffers> POOL =
      new java.util.concurrent.ArrayBlockingQueue<>(POOL_SIZE);

  private static Buffers borrow() {
    var buffers = POOL.poll();
    return buffers != null
        ? buffers
        : new Buffers(
            new double[FULL + 1], new double[FULL + 1], new byte[BONUS_FAILURES + 1][FULL]);
  }

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
    return solve(rule.baseRateBp(), base, choices);
  }

  /**
   * 팔 보호구(완갑) 한 단계(#724 후속). 숨결 두 가지(용암·빙하)는 증가량·최대 개수가 같아, 시도마다 쓸 숨결 수 k(0~두 최대의 합)를 고르고 같은 k면 싼
   * 숨결부터 채운다(같은 확률에 가장 싼 조합). 책은 없다. 재료·파편 값이 없으면 null, 값이 없는 숨결은 쓰지 않는다. 증가량이 다른 숨결이 섞인 단계는 지원하지
   * 않는다(IllegalArgumentException).
   */
  public static Result calculate(VambraceRules.Stage rule, Map<String, ? extends Number> prices) {
    double base = rule.gold();
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
    var priced =
        rule.breaths().stream()
            .filter(breath -> prices.get(breath.name()) != null)
            .sorted(
                java.util.Comparator.comparingDouble(
                    (VambraceRules.Breath breath) -> prices.get(breath.name()).doubleValue()))
            .toList();
    for (var breath : priced)
      if (breath.rateBp() != priced.get(0).rateBp())
        throw new IllegalArgumentException("증가량이 다른 숨결이 섞인 단계는 지원하지 않습니다.");
    int total = priced.stream().mapToInt(VambraceRules.Breath::maxCount).sum();
    var choices = new ArrayList<Choice>();
    for (int breaths = 0; breaths <= total; breaths++) {
      double extra = 0;
      int left = breaths;
      int second = 0;
      for (int i = 0; i < priced.size(); i++) {
        var breath = priced.get(i);
        int used = Math.min(left, breath.maxCount());
        extra += used * prices.get(breath.name()).doubleValue();
        if (i > 0) second += used;
        left -= used;
      }
      choices.add(
          new Choice(
              breaths, false, breaths == 0 ? 0 : priced.get(0).rateBp() * breaths, extra, second));
    }
    return solve(rule.baseRateBp(), base, choices);
  }

  /** 선택지 목록으로 회차별 최적을 푼다. 선택지는 127개까지(선택표가 byte). */
  private static Result solve(double baseRateBp, double base, List<Choice> choices) {
    int size = choices.size();
    if (size > Byte.MAX_VALUE) throw new IllegalArgumentException("선택지가 너무 많습니다: " + size);
    // 실패 n회마다 선택지별 실패 확률·비용·기운 증가는 기운과 무관하다.
    var fail = new double[BONUS_FAILURES + 1][size];
    var cost = new double[size];
    var increment = new int[BONUS_FAILURES + 1][size];
    for (int c = 0; c < size; c++) cost[c] = base + choices.get(c).extraGold();
    for (int n = 0; n <= BONUS_FAILURES; n++)
      for (int c = 0; c < size; c++) {
        double rateBp = baseRateBp * (1 + 0.1 * n) + choices.get(c).bonusBp();
        double p = Math.min(10_000, rateBp) / 10_000;
        fail[n][c] = 1 - p;
        increment[n][c] =
            RefiningPlan.artisanGainHundredths(Math.min(10_000, rateBp)) * (FULL / 10_000);
      }
    // 두 줄과 선택표는 풀에서 빌려 다시 쓴다(#287 P1·#292). 매번 모든 칸을 새로 채운다.
    var buffers = borrow();
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
    // 그 회차까지 실패만 이어질 확률(reach)로 기대 시도·숨결·책 수를 더한다(실패 경로가 하나라 그대로 합).
    double reach = 1;
    double attempts = 0;
    double breaths = 0;
    double books = 0;
    double seconds = 0;
    int firstRateBp = 0;
    while (energy < FULL && path.size() < 10_000) {
      int n = Math.min(failures, BONUS_FAILURES);
      int c = picks[n][energy];
      var choice = choices.get(c);
      path.add(choice.label());
      usesBreath |= choice.breaths() > 0;
      usesBook |= choice.book();
      if (path.size() == 1) firstRateBp = (int) Math.round((1 - fail[n][c]) * 10_000);
      attempts += reach;
      breaths += reach * choice.breaths();
      seconds += reach * choice.second();
      if (choice.book()) books += reach;
      if (fail[n][c] <= 0) {
        reach = 0;
        break;
      }
      reach *= fail[n][c];
      energy += increment[n][c];
      failures++;
    }
    // 장인의 기운 100%: 숨결·책 없이 확정 성공하는 마지막 회차.
    if (energy >= FULL) attempts += reach;
    double expected = current[0];
    POOL.offer(buffers);
    return new Result(
        expected,
        usesBreath,
        usesBook,
        List.copyOf(path),
        attempts,
        breaths,
        books,
        firstRateBp,
        seconds);
  }
}
