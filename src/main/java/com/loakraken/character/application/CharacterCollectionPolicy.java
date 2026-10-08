package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 통계 표본 수집기(B-3·F-7). interval마다 최대 1회 공식 호출을 하므로 기본 3초는 분당 20회 상한이다. reserve는 사용자 요청 몫으로 남겨 둘 최근
 * 60초 호출 수다.
 */
@ConfigurationProperties("stats.collector")
public record CharacterCollectionPolicy(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("3s") Duration interval,
    @DefaultValue("40") int reserve,
    @DefaultValue("1640") BigDecimal minItemLevel,
    @DefaultValue("7d") Duration profileRefreshAfter,
    @DefaultValue("7d") Duration rosterRescanAfter) {
  public CharacterCollectionPolicy {
    if (interval == null || interval.toMillis() < 500)
      throw new IllegalArgumentException("수집 주기는 0.5초 이상이어야 합니다.");
    if (reserve < 0) throw new IllegalArgumentException("예비 호출 수는 0 이상이어야 합니다.");
    if (minItemLevel == null || minItemLevel.signum() < 0)
      throw new IllegalArgumentException("최소 아이템 레벨은 0 이상이어야 합니다.");
    if (profileRefreshAfter == null
        || rosterRescanAfter == null
        || profileRefreshAfter.isNegative()
        || rosterRescanAfter.isNegative())
      throw new IllegalArgumentException("갱신 주기는 0 이상이어야 합니다.");
  }
}
