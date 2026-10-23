package com.loakraken.stats.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 직업별 직업 각인(공식 아크 패시브 칭호 ArkPassive.Title) 목록(#618). 근거·확인 시각은 리소스 source에 있다. 랭킹 직업 각인 필터가 받을 수 있는
 * 값과 선택지 목록을 이 한 곳에서 정한다.
 */
public record ClassEngravingCatalog(Map<String, String> source, Map<String, List<String>> classes) {
  public ClassEngravingCatalog {
    source = source == null ? Map.of() : Map.copyOf(source);
    var copy = new LinkedHashMap<String, List<String>>();
    if (classes != null) classes.forEach((name, titles) -> copy.put(name, List.copyOf(titles)));
    classes = java.util.Collections.unmodifiableMap(copy);
  }

  /** 그 직업의 직업 각인(파일 순서). 모르는 직업이면 빈 목록. */
  public List<String> of(String className) {
    return className == null ? List.of() : classes.getOrDefault(className, List.of());
  }

  /** 요청 값을 목록의 표기로 바꾼다. 공백은 비교하지 않는다(공식 표기의 콜론 앞뒤 공백 등). 목록 밖이면 빈 값. */
  public Optional<String> canonical(String className, String value) {
    if (value == null) return Optional.empty();
    var target = compact(value);
    return of(className).stream().filter(title -> compact(title).equals(target)).findFirst();
  }

  public static String compact(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "");
  }
}
