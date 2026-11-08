package com.loakraken.character.domain;

import com.loakraken.common.gamedata.AccessoryMainStatRange;
import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 4티어 장신구 기본 효과 힘민지가 그 연마 단계 범위의 몇 %인지(흡수 #6). 범위는 시세 장신구 검색과 같은 공식 아이템 사전 표({@link
 * AccessoryMainStatRange})이고 연마 단계 = 연마 효과 줄 수다. 공식 Tooltip 기본 효과에는 힘·민첩·지능 세 줄이 같은 값으로 나오므로(다른 직업
 * 몫은 흐린 색) 첫 줄 값을 쓴다.
 */
public record AccessoryBaseStat(
    int polishStage, int mainStat, int mainStatMin, int mainStatMax, BigDecimal mainStatRangePct) {
  private static final Pattern MAIN_STAT = Pattern.compile("^(힘|민첩|지능) \\+([0-9]+)$");
  private static final Set<String> GRADES = Set.of("고대", "유물");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** 4티어 고대·유물 목걸이·귀걸이·반지가 아니거나, 힘민지 줄이 없거나, 연마 줄이 3개를 넘으면 null. */
  public static AccessoryBaseStat of(
      String type, String grade, CharacterLoadout.EquipmentDetail detail) {
    var slot = AccessoryMainStatRange.Slot.fromOfficial(type);
    if (slot == null || detail == null || !GRADES.contains(grade)) return null;
    if (detail.tier() == null || detail.tier() != 4) return null;
    int stage = detail.polishEffects().size();
    if (stage > AccessoryMainStatRange.MAX_STAGE) return null;
    Integer value = mainStat(detail);
    if (value == null) return null;
    int min = AccessoryMainStatRange.min(slot, stage);
    int max = AccessoryMainStatRange.max(slot, stage);
    var pct = AccessoryMainStatRange.rangePct(slot, stage, value);
    // 범위 밖이면 표와 맞지 않는 값이라 %를 내지 않는다(표 점검 대상).
    if (pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) pct = null;
    return new AccessoryBaseStat(stage, value, min, max, pct);
  }

  /** 힘민지가 그 단계 범위 밖이라 %를 내지 않았는가(범위표 점검 대상). */
  public boolean outOfRange() {
    return mainStatRangePct == null;
  }

  private static Integer mainStat(CharacterLoadout.EquipmentDetail detail) {
    for (var group : detail.effects()) {
      if (group == null || !"기본 효과".equals(group.title()) || group.lines() == null) continue;
      for (var line : group.lines()) {
        if (line == null) continue;
        var matcher = MAIN_STAT.matcher(line.strip().replace(",", ""));
        if (matcher.matches()) {
          try {
            return Integer.parseInt(matcher.group(2));
          } catch (NumberFormatException exception) {
            return null;
          }
        }
      }
    }
    return null;
  }
}
