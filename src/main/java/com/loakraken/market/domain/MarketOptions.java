package com.loakraken.market.domain;

import java.util.List;

public record MarketOptions(
    List<Category> categories,
    List<String> itemGrades,
    List<Integer> itemTiers,
    List<String> classes) {
  public MarketOptions {
    categories = List.copyOf(categories);
    itemGrades = List.copyOf(itemGrades);
    itemTiers = List.copyOf(itemTiers);
    classes = List.copyOf(classes);
    if (itemGrades.stream().anyMatch(String::isBlank)
        || classes.stream().anyMatch(String::isBlank)
        || itemTiers.stream().anyMatch(tier -> tier < 1))
      throw new IllegalArgumentException("잘못된 거래소 옵션");
  }

  public record Category(Integer code, String codeName, List<Subcategory> subs) {
    public Category {
      if (code == null || code < 0 || codeName == null || codeName.isBlank())
        throw new IllegalArgumentException("잘못된 카테고리");
      // 하위 목록 미제공과 빈 목록을 구분한다.
      if (subs != null) subs = List.copyOf(subs);
    }
  }

  public record Subcategory(Integer code, String codeName) {
    public Subcategory {
      if (code == null || code < 0 || codeName == null || codeName.isBlank())
        throw new IllegalArgumentException("잘못된 하위 카테고리");
    }
  }
}
