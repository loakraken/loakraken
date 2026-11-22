package com.loakraken.character.domain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 젬 가공 정책 표(M49 2단계, 미리 계산해 둔 리소스 combat-power/ark-grid-gem-tables.json). 등급(HERO·RARE) × 초기화(끔·켬)
 * × 목표 합 T마다 분배 직후 젬에서 정책 '합 ≥ T가 되면 완료'로 끝까지 간 끝 레벨 멀티셋 분포(합 1), 기대 가공 골드, 기대 초기화권 수. 표는 테스트
 * ArkGridGemTableGeneratorTest가 {@link ArkGridGemProcessing}으로 다시 만든다(환경 변수
 * GEM_TABLES_GENERATE=true). 규칙 버전이 가공 규칙과 다르면 읽지 않는다.
 */
public record ArkGridGemTables(String ruleVersion, List<ArkGridGemProcessing.Table> tables) {
  public static final String RESOURCE = "/combat-power/ark-grid-gem-tables.json";

  public ArkGridGemTables {
    tables = List.copyOf(tables);
  }

  public Optional<ArkGridGemProcessing.Table> find(String grade, boolean reset, int target) {
    return tables.stream()
        .filter(t -> t.grade().equals(grade) && t.reset() == reset && t.target() == target)
        .findFirst();
  }

  /** 등급·초기화의 모든 목표 표. */
  public List<ArkGridGemProcessing.Table> all(String grade, boolean reset) {
    return tables.stream().filter(t -> t.grade().equals(grade) && t.reset() == reset).toList();
  }

  private static final ArkGridGemTables INSTANCE = read();

  public static ArkGridGemTables load() {
    return INSTANCE;
  }

  private static ArkGridGemTables read() {
    try (var input = ArkGridGemTables.class.getResourceAsStream(RESOURCE)) {
      if (input == null) throw new IllegalStateException("젬 가공 정책 표 파일이 없습니다.");
      var root = JsonMapper.builder().build().readTree(input);
      var version = root.path("ruleVersion").asString();
      if (!version.equals(ArkGridGemProcessing.rules().ruleVersion()))
        throw new IllegalStateException("젬 가공 정책 표의 규칙 버전이 가공 규칙과 다릅니다: " + version);
      var tables = new ArrayList<ArkGridGemProcessing.Table>();
      for (var node : root.path("tables")) {
        var terminal = new double[node.path("terminal").size()];
        for (int i = 0; i < terminal.length; i++)
          terminal[i] = node.path("terminal").get(i).asDouble();
        double sum = 0;
        for (double value : terminal) sum += value;
        if (Math.abs(sum - 1) > 1e-9)
          throw new IllegalStateException("젬 가공 정책 표의 끝 분포 합이 1이 아닙니다.");
        tables.add(
            new ArkGridGemProcessing.Table(
                node.path("grade").asString(),
                node.path("reset").asBoolean(),
                node.path("target").asInt(),
                node.path("successProbability").asDouble(),
                terminal,
                node.path("expectedGold").asDouble(),
                node.path("expectedTickets").asDouble()));
      }
      return new ArkGridGemTables(version, tables);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
