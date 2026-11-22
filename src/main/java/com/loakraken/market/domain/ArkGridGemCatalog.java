package com.loakraken.market.domain;

import java.util.List;
import java.util.Optional;

/** 아크 그리드 젬 거래소 품목(M49 G5). */
public record ArkGridGemCatalog(String source, List<Gem> gems) {
  /** 공식 상품 ID, 공식 품목 이름, 젬 종류, 등급. */
  public record Gem(long id, String name, String type, String grade) {}

  public static ArkGridGemCatalog load() {
    return new ArkGridGemCatalog("", List.of());
  }

  public Optional<Gem> find(String type, String grade) {
    return Optional.empty();
  }
}
