package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterRetentionService;
import com.loakraken.common.lostark.LeaderLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** B9 보유 기간 정리(#141·#142). 매일 04:30 KST, 여러 인스턴스 중 리더 임대를 얻은 한 곳만 돈다. */
@Component
@ConditionalOnProperty(
    prefix = "character.retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class CharacterRetentionScheduler {
  private static final Logger log = LoggerFactory.getLogger(CharacterRetentionScheduler.class);
  static final String LEASE = "character-retention";
  private final CharacterRetentionService retention;
  private final LeaderLease lease;

  CharacterRetentionScheduler(CharacterRetentionService retention, LeaderLease lease) {
    this.retention = retention;
    this.lease = lease;
  }

  @Scheduled(cron = "${character.retention.cron:0 30 4 * * *}", zone = "Asia/Seoul")
  void purge() {
    if (!lease.hold(LEASE, Duration.ofMinutes(30))) return;
    MDC.put("entryPoint", "retention");
    try {
      var result = retention.purge();
      log.info("보유 기간 정리: 캐릭터 {}명, 조회수 일별 {}행", result.characters(), result.views());
    } catch (DataAccessException exception) {
      log.warn("보유 기간 정리에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    } finally {
      MDC.remove("entryPoint");
      lease.release(LEASE);
    }
  }
}
