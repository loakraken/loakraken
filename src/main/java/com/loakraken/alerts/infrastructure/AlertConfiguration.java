package com.loakraken.alerts.infrastructure;

import com.loakraken.alerts.application.AlertProperties;
import com.loakraken.alerts.application.AlertService;
import com.loakraken.alerts.application.AlertStore;
import com.loakraken.alerts.application.PushSender;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 시세 알림 웹 푸시(M33). 플래그와 VAPID 키가 모두 있을 때만 빈을 만든다. 키가 잘못되면 시작에 실패한다(조용히 꺼지지 않게). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertProperties.class)
@Conditional(AlertsEnabledCondition.class)
class AlertConfiguration {
  @Bean
  @ConditionalOnMissingBean
  PushTransport pushTransport() {
    return new PushTransport.Http();
  }

  @Bean
  WebPushCrypto.VapidKeys vapidKeys(AlertProperties properties) {
    return WebPushCrypto.vapidKeys(properties.vapidPublicKey(), properties.vapidPrivateKey());
  }

  @Bean
  AlertStore alertStore(JdbcClient jdbc) {
    return new PostgresAlertStore(jdbc);
  }

  @Bean
  PushSender pushSender(
      PushTransport transport,
      WebPushCrypto.VapidKeys keys,
      AlertProperties properties,
      Clock clock) {
    return new WebPushSender(
        transport,
        new WebPushCrypto(new SecureRandom()),
        keys,
        properties.vapidSubject(),
        properties.pushTtl(),
        clock);
  }

  @Bean
  AlertService alertService(
      AlertStore store, PushSender sender, AlertProperties properties, Clock clock) {
    return new AlertService(
        store,
        sender,
        properties,
        clock,
        new SecureRandom(),
        WebPushCrypto::validateSubscriptionKeys);
  }
}
