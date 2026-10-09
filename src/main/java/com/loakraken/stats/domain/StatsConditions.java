package com.loakraken.stats.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.StringJoiner;

/**
 * 정규화한 통계 조건. 응답의 conditions로 그대로 돌려주고 캐시 키로도 쓴다.
 *
 * @param representativeOnly 원정대(같은 서버·같은 계정)에서 아이템 레벨이 가장 높은 캐릭터만. 원정대를 아직 훑지 않은 캐릭터는 자기 자신이 대표다.
 */
public record StatsConditions(
    String server,
    StatsRules.Role role,
    String className,
    int activeDays,
    BigDecimal itemLevelMin,
    BigDecimal itemLevelMax,
    BigDecimal combatPowerMin,
    BigDecimal combatPowerMax,
    boolean representativeOnly) {
  public StatsConditions {
    server = blankToNull(server);
    className = blankToNull(className);
    if (!StatsRules.ACTIVE_DAYS.contains(activeDays))
      throw new IllegalArgumentException("activeDays는 7, 30, 90 중 하나입니다.");
    if (itemLevelMin == null) itemLevelMin = StatsRules.DEFAULT_ITEM_LEVEL_MIN;
    itemLevelMin = strip(itemLevelMin);
    itemLevelMax = strip(itemLevelMax);
    combatPowerMin = strip(combatPowerMin);
    combatPowerMax = strip(combatPowerMax);
  }

  public static StatsConditions defaults() {
    return new StatsConditions(
        null, null, null, StatsRules.DEFAULT_ACTIVE_DAYS, null, null, null, null, false);
  }

  public boolean validRange() {
    return (itemLevelMax == null || itemLevelMin.compareTo(itemLevelMax) <= 0)
        && (combatPowerMin == null
            || combatPowerMax == null
            || combatPowerMin.compareTo(combatPowerMax) <= 0);
  }

  public Instant since(Instant now) {
    return now.minus(Duration.ofDays(activeDays));
  }

  public StatsConditions withServer(String value) {
    return new StatsConditions(
        value,
        role,
        className,
        activeDays,
        itemLevelMin,
        itemLevelMax,
        combatPowerMin,
        combatPowerMax,
        representativeOnly);
  }

  public StatsConditions withClassName(String value) {
    return new StatsConditions(
        server,
        role,
        value,
        activeDays,
        itemLevelMin,
        itemLevelMax,
        combatPowerMin,
        combatPowerMax,
        representativeOnly);
  }

  public String cacheKey() {
    var key = new StringJoiner("|");
    key.add(String.valueOf(server))
        .add(String.valueOf(role))
        .add(String.valueOf(className))
        .add(String.valueOf(activeDays))
        .add(itemLevelMin.toPlainString())
        .add(plain(itemLevelMax))
        .add(plain(combatPowerMin))
        .add(plain(combatPowerMax))
        .add(String.valueOf(representativeOnly));
    return key.toString();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /**
   * 소수 둘째 자리로 반올림한 뒤 끝의 0을 지운다(#108). 아이템 레벨·전투력은 공식 값이 소수 둘째 자리까지라 결과가 같고, 자릿수만 바꾼 요청이 캐시 키를 무한히
   * 늘리지 못한다.
   */
  public static BigDecimal strip(BigDecimal value) {
    return value == null
        ? null
        : value
            .setScale(StatsRules.VALUE_SCALE, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros();
  }

  private static String plain(BigDecimal value) {
    return value == null ? "null" : value.toPlainString();
  }
}
