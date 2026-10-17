package com.loakraken.alerts.infrastructure;

import com.loakraken.alerts.application.AlertService;
import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.market.application.MarketPricesObserved;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 시세 알림 작업(M33): 예열 가격 판정(이벤트), 발송(주기), 오래된 구독 삭제(주기). 발송·삭제는 리더 임대로 한 인스턴스만 돈다. 실패는 종류만 남긴다. */
@Component
@Conditional(AlertsEnabledCondition.class)
class AlertJobs {
  private static final Logger log = LoggerFactory.getLogger(AlertJobs.class);
  static final String DISPATCH_LEASE = "alerts-dispatch";
  static final String CLEANUP_LEASE = "alerts-cleanup";

  private final AlertService alerts;
  private final LeaderLease lease;

  AlertJobs(AlertService alerts, LeaderLease lease) {
    this.alerts = alerts;
    this.lease = lease;
  }

  @EventListener
  void onPrices(MarketPricesObserved event) {
    try {
      alerts.onPrices(event);
    } catch (RuntimeException exception) {
      log.warn("시세 알림 판정에 실패했습니다. exceptionType={}", exception.getClass().getName());
    }
  }

  @Scheduled(
      fixedDelayString = "${alerts.push.dispatch-interval:15s}",
      initialDelayString = "${alerts.push.dispatch-interval:15s}")
  void dispatch() {
    if (!lease.hold(DISPATCH_LEASE, Duration.ofMinutes(2))) return;
    try {
      alerts.dispatch();
    } catch (RuntimeException exception) {
      log.warn("시세 알림 발송 주기에 실패했습니다. exceptionType={}", exception.getClass().getName());
    }
  }

  @Scheduled(
      fixedDelayString = "${alerts.push.cleanup-interval:1h}",
      initialDelayString = "${alerts.push.cleanup-interval:1h}")
  void cleanup() {
    if (!lease.hold(CLEANUP_LEASE, Duration.ofMinutes(10))) return;
    try {
      alerts.cleanup();
    } catch (RuntimeException exception) {
      log.warn("오래된 알림 구독 삭제에 실패했습니다. exceptionType={}", exception.getClass().getName());
    }
  }
}
