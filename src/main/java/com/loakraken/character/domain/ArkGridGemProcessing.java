package com.loakraken.character.domain;

import java.util.List;

/** 아크 그리드 젬 가공(M49 2단계). 뼈대. */
public final class ArkGridGemProcessing {
  public record Rules(String ruleVersion, List<Integer> targets) {}

  public record Table(
      String grade,
      boolean reset,
      int target,
      double successProbability,
      double[] terminal,
      double expectedGold,
      double expectedTickets) {}

  public ArkGridGemProcessing(Rules rules) {}

  public static Rules rules() {
    return new Rules("", List.of());
  }

  public int tupleCount() {
    return 0;
  }

  public int index(int[] levels) {
    return 0;
  }

  public int permutations(int index) {
    return 0;
  }

  public Table table(String grade, boolean reset, int target) {
    return new Table(grade, reset, target, 0, new double[0], 0, 0);
  }

  public double shownSuccess(
      String grade, int target, int[] levels, int n, int k, int ratio, boolean tried, List<String> shown) {
    return 0;
  }

  public double shownSuccess(
      String grade,
      int target,
      int[] levels,
      int n,
      int k,
      int ratio,
      boolean tried,
      boolean reset,
      List<String> shown) {
    return 0;
  }
}
