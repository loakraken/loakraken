package com.loakraken.alerts.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 시세 알림 웹 푸시(M33). 기본 꺼짐이다. VAPID 키가 없으면 켜도 경로가 404다(키를 바꾸면 기존 구독이 모두 무효가 된다).
 *
 * @param enabled 경로·발송 작업을 켠다(ALERTS_PUSH_ENABLED)
 * @param vapidPublicKey base64url 비압축 P-256 공개 키 65바이트
 * @param vapidPrivateKey base64url 개인 키 32바이트. 환경 변수로만 받고 로그·응답에 내지 않는다
 * @param vapidSubject VAPID sub(mailto: 또는 https: 주소)
 * @param maxConditionsPerSubscription 구독 하나의 조건 수 상한
 * @param maxSubscriptions 전체 구독 상한(넘으면 503)
 * @param sendsPerMinute 분당 발송 상한(넘으면 다음 주기로 미룸)
 * @param resendInterval 같은 조건의 최소 재발송 간격
 * @param inactiveRetention 이 기간 동안 쓰지 않은 구독은 지운다
 * @param itemFreshness 이 기간 안에 가격을 관측한 품목에만 조건을 걸 수 있다
 * @param dispatchInterval 발송 작업 주기
 * @param cleanupInterval 오래된 구독 삭제 작업 주기
 * @param pushTtl 푸시 서비스가 메시지를 보관할 시간(TTL 헤더)
 */
@ConfigurationProperties("alerts.push")
public record AlertProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("") String vapidPublicKey,
    @DefaultValue("") String vapidPrivateKey,
    @DefaultValue("") String vapidSubject,
    @DefaultValue("10") int maxConditionsPerSubscription,
    @DefaultValue("20000") int maxSubscriptions,
    @DefaultValue("600") int sendsPerMinute,
    @DefaultValue("6h") Duration resendInterval,
    @DefaultValue("90d") Duration inactiveRetention,
    @DefaultValue("2h") Duration itemFreshness,
    @DefaultValue("15s") Duration dispatchInterval,
    @DefaultValue("1h") Duration cleanupInterval,
    @DefaultValue("1h") Duration pushTtl) {
  public AlertProperties {
    if (maxConditionsPerSubscription < 1
        || maxSubscriptions < 1
        || sendsPerMinute < 1
        || resendInterval.isNegative()
        || inactiveRetention.isNegative()
        || inactiveRetention.isZero()
        || itemFreshness.isNegative()
        || itemFreshness.isZero()) throw new IllegalArgumentException("시세 알림 설정값이 올바르지 않습니다.");
  }

  /** 키 세 값이 모두 있어야 실제로 쓸 수 있다. */
  public boolean configured() {
    return !vapidPublicKey.isBlank() && !vapidPrivateKey.isBlank() && !vapidSubject.isBlank();
  }

  @Override
  public String toString() {
    return "AlertProperties[enabled=" + enabled + ", configured=" + configured() + "]";
  }
}
