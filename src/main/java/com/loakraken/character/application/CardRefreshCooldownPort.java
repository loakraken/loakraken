package com.loakraken.character.application;

import java.time.Duration;
import java.util.Optional;

/** 원정대 카드 기록 채우기 쿨다운. 여러 탭·사용자·인스턴스가 같이 눌러도 한 곳에서 지킨다. */
public interface CardRefreshCooldownPort {
  /** 쿨다운을 잡으면 empty, 이미 잡혀 있으면 남은 시간. */
  Optional<Duration> acquire(String rosterKey, Duration cooldown);
}
