package com.loakraken.market.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 보석 매물 직업·스킬 필터 목록(리소스 market/gem-skill-options.json). 공식 경매장 검색 옵션(/auctions/options)의 SkillOptions
 * 가운데 보석 트라이포드가 있는 스킬만 담는다. kindOptions는 보석 종류 → 공식 검색 SecondOption 값(겁화 = 피해 증가 500, 작열 = 재사용 대기시간
 * 감소 2700, 공식 검색으로 거르기 확인). 근거·확인 날짜는 리소스 source·checkedOn에 있다.
 */
public record GemSkillCatalog(
    String source, String checkedOn, Map<String, Integer> kindOptions, List<GemClass> classes) {
  public GemSkillCatalog {
    kindOptions = kindOptions == null ? Map.of() : Map.copyOf(kindOptions);
    classes = classes == null ? List.of() : List.copyOf(classes);
  }

  public record GemClass(String className, List<GemSkill> skills) {
    public GemClass {
      skills = skills == null ? List.of() : List.copyOf(skills);
    }
  }

  /** kinds: 이 스킬로 걸러 찾을 수 있는 보석 종류(겁화·작열). */
  public record GemSkill(int code, String name, List<String> kinds) {
    public GemSkill {
      kinds = kinds == null ? List.of() : List.copyOf(kinds);
    }
  }

  /** 공식 검색에 넣을 조건. skill이 null이면 직업만 거른다. */
  public record Filter(String className, GemSkill skill, int secondOption) {}

  public Optional<GemClass> find(String className) {
    if (className == null) return Optional.empty();
    return classes.stream().filter(value -> value.className().equals(className)).findFirst();
  }

  /**
   * 직업·스킬 조건을 확인한다. 둘 다 null이면 empty(조건 없음). 스킬만 있거나, 목록에 없는 직업, 그 직업에 없는 스킬, 그 스킬로 걸러지지 않는 보석
   * 종류이면 IllegalArgumentException.
   */
  public Optional<Filter> filter(String kind, String className, Integer skillCode) {
    if (className == null && skillCode == null) return Optional.empty();
    if (className == null) throw new IllegalArgumentException("스킬 조건에는 직업이 필요합니다.");
    var gemClass =
        find(className).orElseThrow(() -> new IllegalArgumentException("목록에 없는 직업입니다."));
    var second = kindOptions.get(kind);
    if (second == null) throw new IllegalArgumentException("보석 종류가 올바르지 않습니다.");
    if (skillCode == null) return Optional.of(new Filter(className, null, second));
    var skill =
        gemClass.skills().stream()
            .filter(value -> value.code() == skillCode)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("그 직업에 없는 스킬입니다."));
    if (!skill.kinds().contains(kind))
      throw new IllegalArgumentException("그 스킬로는 찾을 수 없는 보석 종류입니다.");
    return Optional.of(new Filter(className, skill, second));
  }
}
