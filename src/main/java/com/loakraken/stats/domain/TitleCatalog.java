package com.loakraken.stats.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 칭호별 등급·획득 조건(레퍼런스 칭호 데이터 표). 공식 프로필의 칭호는 문자열뿐이라 등급·조건은 이 표에서만 온다. 칭호 문자열은 공식 표기 그대로 정확히 맞춘다. 표에
 * 없는 칭호는 빈 값(웹은 중립 색). 줄마다 source는 공식 주소 또는 '내부 기록'이고 응답에 내지 않는다.
 */
public record TitleCatalog(Map<String, String> source, List<Entry> titles) {
  public record Entry(
      String title, String grade, String parentTitle, String condition, String source) {}

  public TitleCatalog {
    source = source == null ? Map.of() : Map.copyOf(source);
    titles = titles == null ? List.of() : List.copyOf(titles);
  }

  /** 칭호 문자열(공식 표기 그대로) → 줄. 같은 칭호가 두 번이면 앞 줄. 한 번 만들어 들고 쓴다. */
  public Map<String, Entry> index() {
    var map = new LinkedHashMap<String, Entry>();
    for (var entry : titles) map.putIfAbsent(entry.title(), entry);
    return java.util.Collections.unmodifiableMap(map);
  }
}
