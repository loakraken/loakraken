package com.loakraken.character.domain;

import java.util.List;
import java.util.Map;

/**
 * 장신구 연마 옵션별 하·중·상 수치(#251). 리소스 combat-power/polish-values.json이고, 근거는 공식 armory 장비 툴팁 표본(연마 줄 글자색
 * = 등급)이다. 표본에 없던 칸은 null로 두고 추정하지 않는다.
 */
public record CombatPowerPolishValues(
    String version, Map<String, String> source, String accessoryGrade, List<Value> options) {
  public CombatPowerPolishValues {
    source = source == null ? Map.of() : Map.copyOf(source);
    options = options == null ? List.of() : List.copyOf(options);
  }

  /** low·mid·high는 공식 툴팁 표기 그대로("+2.60%", "+390"). percent는 같은 이름의 % 옵션과 고정값 옵션을 가른다. */
  public record Value(String option, boolean percent, String low, String mid, String high) {}
}
