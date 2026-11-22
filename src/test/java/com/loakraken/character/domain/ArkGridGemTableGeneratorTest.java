package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/**
 * 젬 가공 정책 표 만들기(오프라인, M49 2단계). 환경 변수 GEM_TABLES_GENERATE=true일 때만 돈다(보통 실행에서는 건너뜀): 등급(HERO·RARE)
 * × 초기화(끔·켬) × 목표 합 T(규칙 targets)마다 {@link ArkGridGemProcessing#table}로 풀어
 * src/main/resources/combat-power/ ark-grid-gem-tables.json에 쓰고, 걸린 시간을 남긴다.
 */
class ArkGridGemTableGeneratorTest {
  @Test
  void 정책_표를_만든다() throws Exception {
    assumeThat(System.getenv("GEM_TABLES_GENERATE")).isEqualTo("true");
    var rules = ArkGridGemProcessing.rules();
    var jobs = new java.util.ArrayList<Object[]>();
    for (var grade : java.util.List.of("HERO", "RARE"))
      for (boolean reset : new boolean[] {false, true})
        for (int target : rules.targets()) jobs.add(new Object[] {grade, reset, target});
    long started = System.nanoTime();
    var results = new ConcurrentLinkedQueue<ArkGridGemProcessing.Table>();
    jobs.parallelStream()
        .forEach(
            job ->
                results.add(
                    new ArkGridGemProcessing(rules)
                        .table((String) job[0], (Boolean) job[1], (Integer) job[2])));
    long ms = (System.nanoTime() - started) / 1_000_000;
    var sorted =
        results.stream()
            .sorted(
                java.util.Comparator.comparing(ArkGridGemProcessing.Table::grade)
                    .thenComparing(ArkGridGemProcessing.Table::reset)
                    .thenComparingInt(ArkGridGemProcessing.Table::target))
            .toList();
    var json = new StringBuilder();
    json.append("{\n  \"ruleVersion\": \"").append(rules.ruleVersion()).append("\",\n");
    json.append("  \"generatedBy\": \"ArkGridGemTableGeneratorTest (")
        .append(sorted.size())
        .append(" tables, ")
        .append(ms)
        .append(" ms)\",\n  \"tables\": [\n");
    for (int i = 0; i < sorted.size(); i++) {
      var table = sorted.get(i);
      double sum = 0;
      for (double value : table.terminal()) sum += value;
      assertThat(sum).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
      json.append(
          String.format(
              Locale.ROOT,
              "    {\"grade\": \"%s\", \"reset\": %s, \"target\": %d, \"successProbability\": %.12f, \"expectedGold\": %.6f, \"expectedTickets\": %.12f, \"terminal\": [",
              table.grade(),
              table.reset(),
              table.target(),
              table.successProbability(),
              table.expectedGold(),
              table.expectedTickets()));
      var terminal = table.terminal();
      for (int t = 0; t < terminal.length; t++)
        json.append(t == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.15e", terminal[t]));
      json.append("]}").append(i + 1 < sorted.size() ? ",\n" : "\n");
    }
    json.append("  ]\n}\n");
    var path = Path.of("src/main/resources/combat-power/ark-grid-gem-tables.json");
    Files.writeString(path, json, StandardCharsets.UTF_8);
    System.out.printf("GEMTABLES %d tables in %d ms -> %s%n", sorted.size(), ms, path);
  }
}
