package com.loakraken.character.domain;

import java.util.List;
import java.util.Optional;

/** 젬 가공 정책 표(M49 2단계). 뼈대. */
public record ArkGridGemTables(String ruleVersion, List<ArkGridGemProcessing.Table> tables) {
  public static ArkGridGemTables load() {
    return new ArkGridGemTables("", List.of());
  }

  public Optional<ArkGridGemProcessing.Table> find(String grade, boolean reset, int target) {
    return Optional.empty();
  }
}
