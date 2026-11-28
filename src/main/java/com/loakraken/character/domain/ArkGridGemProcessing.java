package com.loakraken.character.domain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아크 그리드 젬 가공(M49 2단계, 계산 검증 명세 §4 REVISED, 2026-11-22). 정책 '포인트 합 ≥ T가 되면 완료'의 성공 확률을 정확히 푸는 동적
 * 계획(뒤로)과, 같은 정책의 끝 상태 분포·기대 가공 골드·기대 초기화권(앞으로).
 *
 * <p>상태: 네 옵션 레벨의 멀티셋(70가지 — 네 옵션의 확률·제외 조건이 같아 위치를 몰라도 된다, 계산 검증 확인), 추가 비율(−1·0·+1), 남은 가공, 다른 항목
 * 보기 횟수, 가공했는지, 초기화를 썼는지. 다른 항목 보기는 가공 횟수를 쓰지 않아 남은 가공보다 많을 수 있다(자르지 않음). 매 차수 보이는 4개는 중복 없이 비복원 가중
 * 추출로 모든 조합을 정확히 센다(제외 조건 묶음마다 한 번). 같은 값이면 위치와 상관없는 규칙으로 고른다: 완료, 다른 항목 보기·초기화는 더 클 때만(계산 검증
 * caveat A). 효과 이름은 정책이 보지 않아 끝 레벨과 독립이고 12가지 순서쌍에 고르다(caveat B).
 */
public final class ArkGridGemProcessing {
  private static final double EPS = 1e-12;

  /** 가공 규칙(리소스 combat-power/ark-grid-gem-processing.json). */
  public record Rules(
      String ruleVersion,
      int maxLevel,
      List<Kind> perOption,
      List<Kind> general,
      Map<String, Grade> grades,
      int baseCostGold,
      List<Integer> targets) {}

  /**
   * 가공 항목 하나. perOption이면 delta만큼 그 옵션 레벨이 바뀌고(excludedAtOrAbove·excludedAtOrBelow), 아니면
   * rerolls·costDelta·제외 조건만 쓴다.
   */
  public record Kind(
      String kind,
      double weight,
      int delta,
      Integer excludedAtOrAbove,
      Integer excludedAtOrBelow,
      int rerolls,
      int costDelta,
      Integer excludedAtCostRatio,
      boolean excludedOnLastTry) {}

  public record Grade(int tries, int rerolls) {}

  /** 한 정책 표: 끝 상태 멀티셋 분포(합 1), 성공(합 ≥ T) 확률, 젬 하나의 기대 가공 골드·기대 초기화권 수. */
  public record Table(
      String grade,
      boolean reset,
      int target,
      double successProbability,
      double[] terminal,
      double expectedGold,
      double expectedTickets) {}

  // 레벨 멀티셋(오름차순 네 값) 70가지.
  private final int[][] tuples;
  private final Map<Integer, Integer> tupleIndex = new HashMap<>();
  private final Rules rules;
  // 제외 조건 묶음(멀티셋 × 추가 비율 × 마지막 가공) → 고를 수 있는 항목과 4개 조합 분포.
  private final Map<Integer, Mask> masks = new HashMap<>();

  private record Choice(int option, Kind kind) {}

  private record Mask(Choice[] choices, int[][] sets, double[] probabilities) {}

  public ArkGridGemProcessing(Rules rules) {
    this.rules = rules;
    var list = new ArrayList<int[]>();
    int max = rules.maxLevel();
    for (int a = 1; a <= max; a++)
      for (int b = a; b <= max; b++)
        for (int c = b; c <= max; c++)
          for (int d = c; d <= max; d++) {
            tupleIndex.put(encode(new int[] {a, b, c, d}), list.size());
            list.add(new int[] {a, b, c, d});
          }
    tuples = list.toArray(int[][]::new);
  }

  /** 레벨 멀티셋 수(70). */
  public int tupleCount() {
    return tuples.length;
  }

  /** i번째 멀티셋(오름차순 네 레벨, 복사본). */
  public int[] tuple(int index) {
    return tuples[index].clone();
  }

  /** 네 레벨(순서 상관없음)의 멀티셋 번호. */
  public int index(int[] levels) {
    var sorted = levels.clone();
    Arrays.sort(sorted);
    return tupleIndex.get(encode(sorted));
  }

  /** 멀티셋의 서로 다른 순서 수 4! / ∏(같은 값 개수)!. */
  public int permutations(int index) {
    var counts = new int[rules.maxLevel() + 1];
    for (int level : tuples[index]) counts[level]++;
    int result = 24;
    for (int count : counts) for (int i = 2; i <= count; i++) result /= i;
    return result;
  }

  private static int encode(int[] sorted) {
    return ((sorted[0] * 8 + sorted[1]) * 8 + sorted[2]) * 8 + sorted[3];
  }

  private static int sum(int[] levels) {
    return levels[0] + levels[1] + levels[2] + levels[3];
  }

  // ---- 4개 조합 분포 ----

  private Mask mask(int tuple, int ratio, boolean lastTry) {
    int key = (tuple * 3 + (ratio + 1)) * 2 + (lastTry ? 1 : 0);
    return masks.computeIfAbsent(key, k -> buildMask(tuple, ratio, lastTry));
  }

  private Mask buildMask(int tuple, int ratio, boolean lastTry) {
    var levels = tuples[tuple];
    var choices = new ArrayList<Choice>();
    for (int option = 0; option < 4; option++)
      for (var kind : rules.perOption()) {
        if (kind.excludedAtOrAbove() != null && levels[option] >= kind.excludedAtOrAbove())
          continue;
        if (kind.excludedAtOrBelow() != null && levels[option] <= kind.excludedAtOrBelow())
          continue;
        choices.add(new Choice(option, kind));
      }
    for (var kind : rules.general()) {
      if (kind.excludedOnLastTry() && lastTry) continue;
      if (kind.excludedAtCostRatio() != null && kind.excludedAtCostRatio() == ratio) continue;
      choices.add(new Choice(-1, kind));
    }
    int m = choices.size();
    var weights = new double[m];
    for (int i = 0; i < m; i++) weights[i] = choices.get(i).kind().weight();
    var sets = new ArrayList<int[]>();
    var probabilities = new ArrayList<Double>();
    int[][] orders = permutations4();
    double all = 0;
    for (double w : weights) all += w;
    for (int a = 0; a < m; a++)
      for (int b = a + 1; b < m; b++)
        for (int c = b + 1; c < m; c++)
          for (int d = c + 1; d < m; d++) {
            int[] set = {a, b, c, d};
            double total = 0;
            for (var order : orders) {
              double remaining = all;
              double p = 1;
              for (int j = 0; j < 4; j++) {
                double w = weights[set[order[j]]];
                p *= w / remaining;
                remaining -= w;
              }
              total += p;
            }
            sets.add(set);
            probabilities.add(total);
          }
    var probs = new double[probabilities.size()];
    for (int i = 0; i < probs.length; i++) probs[i] = probabilities.get(i);
    return new Mask(choices.toArray(Choice[]::new), sets.toArray(int[][]::new), probs);
  }

  private static int[][] permutations4() {
    var result = new ArrayList<int[]>();
    for (int a = 0; a < 4; a++)
      for (int b = 0; b < 4; b++)
        for (int c = 0; c < 4; c++)
          for (int d = 0; d < 4; d++)
            if (a != b && a != c && a != d && b != c && b != d && c != d)
              result.add(new int[] {a, b, c, d});
    return result.toArray(int[][]::new);
  }

  // ---- 값 표 ----

  /** 한 등급·목표의 값 표. layer 0 = 초기화 가능, 1 = 초기화 없음(썼거나 끔). */
  private final class Solver {
    final int tries;
    final int baseRerolls;
    final int maxRerolls;
    final int target;
    final boolean[] success;
    // value[layer][n][k][ratio][tuple], 가공한 뒤(tried) 상태.
    final double[][][][][] value;
    // start[layer]: 분배 직후(가공 전) 상태의 값(초기화 뒤 시작과 같다).
    final double[] start = new double[2];
    final int startTuple;

    Solver(Grade grade, int target, int[] startLevels) {
      this.tries = grade.tries();
      this.baseRerolls = grade.rerolls();
      this.maxRerolls = grade.rerolls() + 2 * grade.tries();
      this.target = target;
      this.startTuple = index(startLevels);
      success = new boolean[tuples.length];
      for (int t = 0; t < tuples.length; t++) success[t] = sum(tuples[t]) >= target;
      value = new double[2][tries + 1][maxRerolls + 1][3][tuples.length];
      for (int layer = 1; layer >= 0; layer--) solveLayer(layer);
    }

    double succ(int tuple) {
      return success[tuple] ? 1 : 0;
    }

    /** 가공 뒤 상태(n·k·ratio·tuple) 값. k는 최대에서 자른다(닿지 않는 칸). */
    double v(int layer, int n, int k, int ratio, int tuple) {
      return value[layer][n][Math.min(k, maxRerolls)][ratio + 1][tuple];
    }

    void solveLayer(int layer) {
      for (int t = 0; t < tuples.length; t++)
        for (int k = 0; k <= maxRerolls; k++)
          for (int r = 0; r < 3; r++) value[layer][0][k][r][t] = succ(t);
      for (int n = 1; n <= tries; n++)
        for (int k = 0; k <= maxRerolls; k++)
          for (int r = -1; r <= 1; r++)
            for (int t = 0; t < tuples.length; t++)
              value[layer][n][k][r + 1][t] = stateValue(layer, n, k, r, t, true);
      start[layer] = stateValue(layer, tries, baseRerolls, 0, startTuple, false);
    }

    /** 다음 상태 값(가공 한 번 뒤, 가공함). */
    double next(int layer, int n, int k, int ratio, int tuple, Choice choice) {
      int nt = tuple;
      int nr = ratio;
      int nk = k;
      if (choice.option() >= 0) {
        var levels = tuples[tuple].clone();
        levels[choice.option()] += choice.kind().delta();
        nt = index(levels);
      } else {
        nr = Math.max(-1, Math.min(1, ratio + choice.kind().costDelta()));
        nk = k + choice.kind().rerolls();
      }
      return v(layer, n - 1, nk, nr, nt);
    }

    /** 보이는 4개를 보기 전 값 E_S[W]. tried = 한 번이라도 가공했는지(완료·다른 항목 보기 가능). */
    double stateValue(int layer, int n, int k, int ratio, int tuple, boolean tried) {
      var mask = mask(tuple, ratio, n == 1);
      var after = new double[mask.choices().length];
      for (int i = 0; i < after.length; i++)
        after[i] = next(layer, n, k, ratio, tuple, mask.choices()[i]);
      double other = Double.NEGATIVE_INFINITY;
      if (tried) {
        other = succ(tuple);
        if (k > 0) other = Math.max(other, v(layer, n, k - 1, ratio, tuple));
        if (layer == 0) other = Math.max(other, start[1]);
      }
      double total = 0;
      var sets = mask.sets();
      var probabilities = mask.probabilities();
      for (int s = 0; s < sets.length; s++) {
        var set = sets[s];
        double process = (after[set[0]] + after[set[1]] + after[set[2]] + after[set[3]]) / 4;
        total += probabilities[s] * Math.max(other, process);
      }
      return total;
    }

    /** 보이는 4개(set: 항목 번호)를 본 뒤의 값(검증 사례용). */
    double shownValue(
        int layer, int n, int k, int ratio, int tuple, boolean tried, List<Choice> shown) {
      double process = 0;
      for (var choice : shown) process += next(layer, n, k, ratio, tuple, choice);
      process /= shown.size();
      double other = Double.NEGATIVE_INFINITY;
      if (tried) {
        other = succ(tuple);
        if (k > 0) other = Math.max(other, v(layer, n, k - 1, ratio, tuple));
        if (layer == 0) other = Math.max(other, start[1]);
      }
      return Math.max(other, process);
    }
  }

  // ---- 앞으로: 끝 상태 분포 ----

  /** 분배 직후 젬(네 옵션 1)에서 시작해 정책 '합 ≥ target'으로 끝까지 갔을 때의 표. reset = 초기화권을 쓸 수 있는지. */
  public Table table(String grade, boolean reset, int target) {
    var g = rules.grades().get(grade);
    if (g == null) throw new IllegalArgumentException("모르는 등급: " + grade);
    var solver = new Solver(g, target, new int[] {1, 1, 1, 1});
    int layers = 2;
    // mass[layer][n][k][ratio][tuple] (가공 뒤), startMass[layer].
    var mass = new double[layers][g.tries() + 1][solver.maxRerolls + 1][3][tuples.length];
    var terminal = new double[tuples.length];
    double gold = 0;
    double tickets = 0;
    var startMass = new double[layers];
    startMass[reset ? 0 : 1] = 1;
    for (int layer = 0; layer < layers; layer++) {
      // 시작(가공 전): 보인 4개 중 하나를 가공하는 것만 된다.
      if (startMass[layer] > 0) {
        var outcome =
            forward(
                solver,
                layer,
                g.tries(),
                g.rerolls(),
                0,
                solver.startTuple,
                false,
                startMass[layer],
                mass,
                terminal);
        gold += outcome[0];
        tickets += outcome[1];
      }
      for (int n = g.tries(); n >= 1; n--)
        for (int k = solver.maxRerolls; k >= 0; k--)
          for (int r = -1; r <= 1; r++)
            for (int t = 0; t < tuples.length; t++) {
              double m = mass[layer][n][k][r + 1][t];
              if (m == 0) continue;
              var outcome = forward(solver, layer, n, k, r, t, true, m, mass, terminal);
              gold += outcome[0];
              tickets += outcome[1];
              if (layer == 0) startMass[1] += outcome[2];
            }
      for (int k = 0; k <= solver.maxRerolls; k++)
        for (int r = 0; r < 3; r++)
          for (int t = 0; t < tuples.length; t++) terminal[t] += mass[layer][0][k][r][t];
    }
    double p = 0;
    for (int t = 0; t < tuples.length; t++) if (solver.success[t]) p += terminal[t];
    return new Table(grade, reset, target, p, terminal, gold, tickets);
  }

  /**
   * 한 상태의 질량을 보인 4개마다 나눈다. 돌려주는 값: [이 상태에서 쓴 기대 가공 골드, 초기화권, 초기화로 넘긴 질량]. 같은 값이면 완료, 다른 항목 보기·초기화는
   * 더 클 때만, 그다음 가공.
   */
  private double[] forward(
      Solver solver,
      int layer,
      int n,
      int k,
      int ratio,
      int tuple,
      boolean tried,
      double m,
      double[][][][][] mass,
      double[] terminal) {
    var mask = mask(tuple, ratio, n == 1);
    var choices = mask.choices();
    var after = new double[choices.length];
    for (int i = 0; i < after.length; i++)
      after[i] = solver.next(layer, n, k, ratio, tuple, choices[i]);
    double complete = tried ? solver.succ(tuple) : Double.NEGATIVE_INFINITY;
    double refresh =
        tried && k > 0 ? solver.v(layer, n, k - 1, ratio, tuple) : Double.NEGATIVE_INFINITY;
    double reset = tried && layer == 0 ? solver.start[1] : Double.NEGATIVE_INFINITY;
    double gold = 0;
    double tickets = 0;
    double toReset = 0;
    double cost = rules.baseCostGold() * (1.0 + ratio);
    var sets = mask.sets();
    var probabilities = mask.probabilities();
    for (int s = 0; s < sets.length; s++) {
      var set = sets[s];
      double share = m * probabilities[s];
      double process = (after[set[0]] + after[set[1]] + after[set[2]] + after[set[3]]) / 4;
      double best = Math.max(process, Math.max(refresh, reset));
      if (complete >= best - EPS) {
        terminal[tuple] += share;
      } else if (refresh > process + EPS && refresh >= reset - EPS) {
        mass[layer][n][k - 1][ratio + 1][tuple] += share;
      } else if (reset > process + EPS) {
        tickets += share;
        toReset += share;
      } else {
        gold += share * cost;
        for (int j = 0; j < 4; j++) {
          var choice = choices[set[j]];
          int nt = tuple;
          int nr = ratio;
          int nk = k;
          if (choice.option() >= 0) {
            var levels = tuples[tuple].clone();
            levels[choice.option()] += choice.kind().delta();
            nt = index(levels);
          } else {
            nr = Math.max(-1, Math.min(1, ratio + choice.kind().costDelta()));
            nk = Math.min(k + choice.kind().rerolls(), solver.maxRerolls);
          }
          mass[layer][n - 1][nk][nr + 1][nt] += share / 4;
        }
      }
    }
    return new double[] {gold, tickets, toReset};
  }

  // ---- 검증 사례: 주어진 상태·보인 4개의 성공 확률 ----

  /**
   * 검증 사례용: levels(의지력·포인트·효과1·효과2 순서 무관) · 남은 가공 n · 다른 항목 보기 k · 추가 비율 · 가공했는지 · 초기화 없음에서, 보인
   * 4개(shown: "PLUS_1:0"처럼 항목:옵션 번호, 일반 항목은 "KEEP")를 본 뒤의 성공 확률.
   */
  public double shownSuccess(
      String grade,
      int target,
      int[] levels,
      int n,
      int k,
      int ratio,
      boolean tried,
      List<String> shown) {
    return shownSuccess(grade, target, levels, n, k, ratio, tried, false, shown);
  }

  /** reset = 아직 초기화권을 쓸 수 있는지(분배 직후로 돌아간다: 네 옵션 1, 가공 횟수·다른 항목 보기·추가 비율 처음). */
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
    var g = rules.grades().get(grade);
    var solver = new Solver(g, target, new int[] {1, 1, 1, 1});
    // 멀티셋 위치로 옵션 번호를 옮긴다: levels[i]가 정렬된 자리 중 어디에 있는지.
    var sorted = levels.clone();
    Arrays.sort(sorted);
    var used = new boolean[4];
    var position = new int[4];
    for (int i = 0; i < 4; i++)
      for (int j = 0; j < 4; j++)
        if (!used[j] && sorted[j] == levels[i]) {
          used[j] = true;
          position[i] = j;
          break;
        }
    var choices = new ArrayList<Choice>();
    for (var name : shown) {
      var parts = name.split(":");
      Kind kind = null;
      for (var candidate : rules.perOption())
        if (candidate.kind().equals(parts[0])) kind = candidate;
      for (var candidate : rules.general()) if (candidate.kind().equals(parts[0])) kind = candidate;
      if (kind == null) throw new IllegalArgumentException("모르는 항목: " + name);
      int option = parts.length > 1 ? position[Integer.parseInt(parts[1])] : -1;
      choices.add(new Choice(option, kind));
    }
    return solver.shownValue(reset ? 0 : 1, n, k, ratio, index(levels), tried, choices);
  }

  // ---- 리소스 ----

  private static final Rules DEFAULT = read("/combat-power/ark-grid-gem-processing.json");

  public static Rules rules() {
    return DEFAULT;
  }

  static Rules read(String resource) {
    try (var input = ArkGridGemProcessing.class.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("젬 가공 규칙 파일이 없습니다.");
      JsonNode root = JsonMapper.builder().build().readTree(input);
      var perOption = new ArrayList<Kind>();
      for (var node : root.path("perOption"))
        perOption.add(
            new Kind(
                node.path("kind").asString(),
                node.path("weightPct").asDouble(),
                node.path("delta").asInt(),
                node.has("excludedAtOrAbove") ? node.path("excludedAtOrAbove").asInt() : null,
                node.has("excludedAtOrBelow") ? node.path("excludedAtOrBelow").asInt() : null,
                0,
                0,
                null,
                false));
      var general = new ArrayList<Kind>();
      for (var node : root.path("general")) {
        var kind = node.path("kind").asString();
        general.add(
            new Kind(
                kind,
                node.path("weightPct").asDouble(),
                0,
                null,
                null,
                node.path("rerolls").asInt(0),
                "COST_PLUS_100".equals(kind) ? 1 : "COST_MINUS_100".equals(kind) ? -1 : 0,
                node.has("excludedAtCostRatio") ? node.path("excludedAtCostRatio").asInt() : null,
                node.path("excludedOnLastTry").asBoolean(false)));
      }
      double total = 0;
      for (var kind : perOption) total += 4 * kind.weight();
      for (var kind : general) total += kind.weight();
      if (Math.abs(total - 100) > 1e-9)
        throw new IllegalStateException("젬 가공 항목 확률 합이 100%가 아닙니다: " + total);
      var grades = new HashMap<String, Grade>();
      root.path("grades")
          .properties()
          .forEach(
              entry ->
                  grades.put(
                      entry.getKey(),
                      new Grade(
                          entry.getValue().path("tries").asInt(),
                          entry.getValue().path("rerolls").asInt())));
      var targets = new ArrayList<Integer>();
      root.path("targets").forEach(node -> targets.add(node.asInt()));
      return new Rules(
          root.path("ruleVersion").asString(),
          root.path("maxLevel").asInt(),
          List.copyOf(perOption),
          List.copyOf(general),
          Map.copyOf(grades),
          root.path("baseCostGold").asInt(),
          List.copyOf(targets));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
