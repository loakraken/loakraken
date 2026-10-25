package com.loakraken.home.infrastructure;

import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.home.application.ShopNoticeAlertService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 상점 공지 알림(#636). 켰을 때만 매일 한 번(기본 10:10 KST), 여러 인스턴스 중 리더 임대를 얻은 한 곳만 돈다. */
@Component
@ConditionalOnProperty(prefix = "shop-notice", name = "enabled", havingValue = "true")
class ShopNoticeJob {
  private static final Logger log = LoggerFactory.getLogger(ShopNoticeJob.class);
  static final String LEASE = "shop-notice";
  private final ShopNoticeAlertService service;
  private final LeaderLease lease;

  ShopNoticeJob(ShopNoticeAlertService service, LeaderLease lease) {
    this.service = service;
    this.lease = lease;
  }

  @Scheduled(cron = "${shop-notice.cron:0 10 10 * * *}", zone = "Asia/Seoul")
  void run() {
    if (!lease.hold(LEASE, Duration.ofMinutes(10))) return;
    MDC.put("entryPoint", "shop-notice");
    try {
      var result = service.run();
      log.info(
          "상점 공지 알림: 상점 공지 {}건, 알림 {}건, 기준만 저장 {}",
          result.shopNotices(),
          result.alerted(),
          result.baseline());
    } catch (RuntimeException exception) {
      // 공식 호출 거절·Slack 실패 등. 다음 날 다시 시도한다(기억하지 않았으므로 같은 공지를 다시 알린다).
      log.warn("상점 공지 알림에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    } finally {
      MDC.remove("entryPoint");
      lease.release(LEASE);
    }
  }
}
