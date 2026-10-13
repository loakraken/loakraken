package com.loakraken.character.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 원정대 캐릭터별 전투력 설정.
 *
 * @param maxTargets 불러오기 1회에 부르는 최대 캐릭터 수
 * @param cooldown 같은 원정대를 다시 불러올 수 있기까지
 * @param staleAfter 이보다 오래된 기록도 다시 부른다
 * @param reserve 공식 호출 게이트에서 사용자 몫으로 남길 호출 수(N13과 같은 등급)
 * @param markerSecret 기록하지 않는 캐릭터(처리정지 목록)의 임시 값·'불러옴' 표시 키를 만드는 HMAC 비밀값(환경 변수). 없으면 불러오기는 404다
 *     (표시 없이 돌면 다음 불러오기 숫자로 처리정지가 드러난다)
 * @param transientTtl 기록하지 않는 캐릭터의 받은 값을 응답에 보여 주는 임시 보관 시간(처리방침 6항)
 */
@ConfigurationProperties("character.roster-combat-power")
public record RosterCombatPowerPolicy(
    @DefaultValue("12") int maxTargets,
    @DefaultValue("10m") Duration cooldown,
    @DefaultValue("24h") Duration staleAfter,
    @DefaultValue("30") int reserve,
    @DefaultValue("") String markerSecret,
    @DefaultValue("5m") Duration transientTtl) {
  public RosterCombatPowerPolicy {
    if (maxTargets < 1) throw new IllegalArgumentException("불러오기 상한은 1 이상이어야 합니다.");
    if (cooldown == null || cooldown.isNegative())
      throw new IllegalArgumentException("쿨다운은 0 이상이어야 합니다.");
    if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero())
      throw new IllegalArgumentException("다시 부르는 기준은 양수여야 합니다.");
    if (reserve < 0) throw new IllegalArgumentException("사용자 몫은 0 이상이어야 합니다.");
    if (transientTtl == null || transientTtl.isNegative() || transientTtl.isZero())
      throw new IllegalArgumentException("임시 보관 시간은 양수여야 합니다.");
  }

  public boolean markerConfigured() {
    return markerSecret != null && markerSecret.length() >= 16;
  }

  @Override
  public String toString() {
    return "RosterCombatPowerPolicy[maxTargets="
        + maxTargets
        + ", cooldown="
        + cooldown
        + ", staleAfter="
        + staleAfter
        + ", reserve="
        + reserve
        + ", markerConfigured="
        + markerConfigured()
        + "]";
  }
}
