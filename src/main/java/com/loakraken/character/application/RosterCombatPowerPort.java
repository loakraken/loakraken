package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** 원정대 캐릭터별 전투력 기록(L19). 우리 DB에 기록된 값만 읽는다. */
public interface RosterCombatPowerPort {
  /** 서버의 이름들 가운데 전투력 기록이 있는 캐릭터의 값과 확인 시각. 처리정지 목록은 기록이 없는 것과 같다. */
  Map<String, Observed> find(String serverName, Collection<String> characterNames);

  record Observed(BigDecimal combatPower, Instant observedAt) {}
}
