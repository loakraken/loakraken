package com.loakraken.character.application;

import java.time.Duration;
import java.util.Optional;

/** 원정대 전투력 불러오기 쿨다운(L19). 여러 탭·사용자·인스턴스가 같이 눌러도 한 곳에서 지킨다. */
public interface RosterRefreshCooldownPort {
  /** 쿨다운을 잡으면 empty, 이미 잡혀 있으면 남은 시간. */
  Optional<Duration> acquire(String rosterKey, Duration cooldown);

  /** 남은 쿨다운. 없거나 확인하지 못하면 empty. */
  Optional<Duration> remaining(String rosterKey);

  /** 한 명도 못 불렀을 때 푼다. 실패해도 예외를 던지지 않는다. */
  void release(String rosterKey);
}
