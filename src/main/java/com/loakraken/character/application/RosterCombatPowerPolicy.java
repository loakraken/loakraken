package com.loakraken.character.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 원정대 캐릭터별 전투력(L19) 설정.
 *
 * @param maxTargets 불러오기 1회에 부르는 최대 캐릭터 수
 * @param cooldown 같은 원정대를 다시 불러올 수 있기까지
 * @param staleAfter 이보다 오래된 기록도 다시 부른다
 * @param reserve 공식 호출 게이트에서 사용자 몫으로 남길 호출 수(N13과 같은 등급)
 */
@ConfigurationProperties("character.roster-combat-power")
public record RosterCombatPowerPolicy(
    @DefaultValue("12") int maxTargets,
    @DefaultValue("10m") Duration cooldown,
    @DefaultValue("24h") Duration staleAfter,
    @DefaultValue("30") int reserve) {
  public RosterCombatPowerPolicy {
    if (maxTargets < 1) throw new IllegalArgumentException("불러오기 상한은 1 이상이어야 합니다.");
    if (cooldown == null || cooldown.isNegative())
      throw new IllegalArgumentException("쿨다운은 0 이상이어야 합니다.");
    if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero())
      throw new IllegalArgumentException("다시 부르는 기준은 양수여야 합니다.");
    if (reserve < 0) throw new IllegalArgumentException("사용자 몫은 0 이상이어야 합니다.");
  }
}
