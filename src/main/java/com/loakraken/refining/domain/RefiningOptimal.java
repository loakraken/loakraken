package com.loakraken.refining.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 회차별 최적 전략(#284). 한 단계에서 시도마다 숨결·책 사용 여부를 따로 골라 기대 골드 비용이 가장 작아지게 한다(단계 전체에 한 조합을 고정하는 것보다 같거나
 * 싸다).
 *
 * <p>상태는 (실패 횟수, 장인의 기운)이다. 실패 보너스는 10회에서 멈추므로 실패 횟수는 0~10이면 충분하고, 장인의 기운은 {@link RefiningPlan}과 같은
 * 0.001%p 단위(0~100000) 정수다. 기운이 100%면 그 시도는 숨결·책 없이 확정 성공이다. 실패하면 다음 상태가 하나로 정해지므로, 최적 정책의 실패
 * 경로(회차별 선택)도 하나다.
 */
public final class RefiningOptimal {
  private static final int FULL = 100_000;
  private static final int BONUS_FAILURES = RefiningPlan.MAX_BONUS_FAILURES;

  /** 회차별 최적의 기대 골드(성장 비용 제외)와 실패 경로에서 숨결·책을 쓰는 회차가 있는지. */
  public record Result(
      double expectedGold, boolean usesBreath, boolean usesBook, List<String> path) {}

  private record Choice(
      String label, double bonusBp, double extraGold, boolean breath, boolean book) {}

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
    var choices = new ArrayList<Choice>();
    choices.add(new Choice("기본", 0, 0, false, false));
    var breath = breathAllowed ? rule.breath() : null;
    var breathPrice = breath == null ? null : prices.get(breath.name());
    var book = bookAllowed ? rule.book() : null;
    var bookPrice = book == null ? null : prices.get(book.name());
    if (breathPrice != null)
      choices.add(
          new Choice(
              "숨결",
              (double) breath.rateBp() * breath.maxCount(),
              breath.maxCount() * breathPrice.doubleValue(),
              true,
              false));
    if (bookPrice != null)
      choices.add(new Choice("책", book.rateBp(), bookPrice.doubleValue(), false, true));
    if (breathPrice != null && bookPrice != null)
      choices.add(
          new Choice(
              "숨결+책",
              (double) breath.rateBp() * breath.maxCount() + book.rateBp(),
              breath.maxCount() * breathPrice.doubleValue() + bookPrice.doubleValue(),
              true,
              true));
    // values[n][E]: 실패 n회(10회 이상은 10)·기운 E에서 이 단계를 끝내는 데 드는 기대 골드.
    var values = new double[BONUS_FAILURES + 1][];
    for (int failures = BONUS_FAILURES; failures >= 0; failures--) {
      var current = new double[FULL + 1];
      var next = failures == BONUS_FAILURES ? current : values[failures + 1];
      for (int energy = FULL; energy >= 0; energy--) {
        if (energy >= FULL) {
          current[energy] = base;
          continue;
        }
        double best = Double.MAX_VALUE;
        for (var choice : choices) {
          double p = probability(rule, failures, choice);
          double cost = base + choice.extraGold();
          if (p < 1) cost += (1 - p) * next[Math.min(FULL, energy + increment(p))];
          if (cost < best) best = cost;
        }
        current[energy] = best;
      }
      values[failures] = current;
    }
    // 실패 경로를 따라 회차별 선택을 다시 찾는다.
    boolean usesBreath = false;
    boolean usesBook = false;
    var path = new ArrayList<String>();
    int failures = 0;
    int energy = 0;
    while (energy < FULL && path.size() < 10_000) {
      int n = Math.min(failures, BONUS_FAILURES);
      var next = values[Math.min(n + 1, BONUS_FAILURES)];
      Choice chosen = null;
      double best = Double.MAX_VALUE;
      double chosenP = 0;
      for (var choice : choices) {
        double p = probability(rule, n, choice);
        double cost = base + choice.extraGold();
        if (p < 1) cost += (1 - p) * next[Math.min(FULL, energy + increment(p))];
        if (cost < best - 1e-9) {
          best = cost;
          chosen = choice;
          chosenP = p;
        }
      }
      path.add(chosen.label());
      usesBreath |= chosen.breath();
      usesBook |= chosen.book();
      if (chosenP >= 1) break;
      energy += increment(chosenP);
      failures++;
    }
    return new Result(values[0][0], usesBreath, usesBook, List.copyOf(path));
  }

  private static double probability(RefiningRules.Stage rule, int failures, Choice choice) {
    double rateBp =
        rule.baseRateBp() * (1 + 0.1 * Math.min(failures, BONUS_FAILURES)) + choice.bonusBp();
    return Math.min(10_000, rateBp) / 10_000;
  }

  /** {@link RefiningPlan}과 같은 반올림(0.001%p 단위). */
  private static int increment(double probability) {
    return (int) Math.round(probability * RefiningPlan.ARTISAN_RATE * FULL);
  }
}
