package com.loakraken.character.domain;

import com.loakraken.common.gamedata.AccessoryMainStatRange;
import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 장신구 기본 효과 힘민지가 그 부위 3단계(연마 뒤) 범위의 몇 %인지(흡수 #6). 비교 기준과 같은 규칙(PM 결정 11-09): 연마 단계와 상관없이 같은 3단계 범위로
 * 재고, 범위 밖은 음수·100 초과 그대로 준다(3티어 장신구도 같은 줄에서 음수). 범위는 공식 아이템 사전 표({@link AccessoryMainStatRange}).
 * 공식 Tooltip 기본 효과에는 힘·민첩·지능 세 줄이 같은 값으로 나오므로(다른 직업 몫은 흐린 색) 첫 줄 값을 쓴다. 연마 단계 = 연마 효과 줄 수.
 */
public record AccessoryBaseStat(
    int polishStage, int mainStat, int mainStatMin, int mainStatMax, BigDecimal mainStatRangePct) {
  private static final Pattern MAIN_STAT = Pattern.compile("^(힘|민첩|지능) \\+([0-9]+)$");
  private static final Set<String> GRADES = Set.of("고대", "유물");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** 3·4티어 고대·유물 목걸이·귀걸이·반지가 아니거나, 힘민지 줄이 없거나, 연마 줄이 3개를 넘으면 null. */
  public static AccessoryBaseStat of(
      String type, String grade, CharacterLoadout.EquipmentDetail detail) {
    var slot = AccessoryMainStatRange.Slot.fromOfficial(type);
    if (slot == null || detail == null || !GRADES.contains(grade)) return null;
    if (detail.tier() == null || (detail.tier() != 3 && detail.tier() != 4)) return null;
    int stage = detail.polishEffects().size();
    if (stage > AccessoryMainStatRange.MAX_STAGE) return null;
    Integer value = mainStat(detail);
    if (value == null) return null;
    // 비교 기준처럼 연마 단계와 상관없이 3단계(연마 뒤) 범위로 잰다.
    int line = AccessoryMainStatRange.MAX_STAGE;
    int min = AccessoryMainStatRange.min(slot, line);
    int max = AccessoryMainStatRange.max(slot, line);
    return new AccessoryBaseStat(
        stage, value, min, max, AccessoryMainStatRange.rangePct(slot, line, value));
  }

  /** 힘민지가 3단계 최댓값보다 큰가(% 100 초과). 범위표에 없는 값이라 표 점검 대상. 최솟값 아래는 연마가 덜 된 장신구·3티어에서 흔한 값이라 해당 없음. */
  public boolean aboveMax() {
    return mainStatRangePct != null && mainStatRangePct.compareTo(HUNDRED) > 0;
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
