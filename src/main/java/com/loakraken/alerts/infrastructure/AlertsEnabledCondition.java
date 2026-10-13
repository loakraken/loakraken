package com.loakraken.alerts.infrastructure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 시세 알림(M33)은 플래그가 켜지고 VAPID 키 세 값이 모두 있을 때만 켠다. 하나라도 없으면 경로·발송 작업이 없다(404). */
public class AlertsEnabledCondition implements Condition {
  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    var env = context.getEnvironment();
    return env.getProperty("alerts.push.enabled", Boolean.class, false)
        && !env.getProperty("alerts.push.vapid-public-key", "").isBlank()
        && !env.getProperty("alerts.push.vapid-private-key", "").isBlank()
        && !env.getProperty("alerts.push.vapid-subject", "").isBlank();
  }
}
