package com.loakraken.alerts.infrastructure;

import com.loakraken.alerts.application.PushSender;
import com.loakraken.alerts.domain.PushEndpointPolicy;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 웹 푸시 발송(M33): RFC 8291 암호화 + RFC 8292 VAPID. 보내기 전에 허용 목록을 다시 확인한다. 로그·예외 메시지에는 푸시 서비스 종류·상태
 * 코드·예외 종류만 남기고 주소·키 원문은 남기지 않는다.
 */
class WebPushSender implements PushSender {
  private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
  private static final Duration JWT_LIFETIME = Duration.ofHours(12).minusMinutes(5);

  private final PushTransport transport;
  private final WebPushCrypto crypto;
  private final WebPushCrypto.VapidKeys keys;
  private final String subject;
  private final Duration ttl;
  private final Clock clock;

  WebPushSender(
      PushTransport transport,
      WebPushCrypto crypto,
      WebPushCrypto.VapidKeys keys,
      String subject,
      Duration ttl,
      Clock clock) {
    this.transport = transport;
    this.crypto = crypto;
    this.keys = keys;
    this.subject = subject;
    this.ttl = ttl;
    this.clock = clock;
  }

  @Override
  public Result send(String endpoint, String p256dh, String auth, byte[] payload) {
    var service = PushEndpointPolicy.check(endpoint);
    if (service.isEmpty()) {
      log.warn("허용 목록 밖 알림 구독이라 보내지 않았습니다.");
      return Result.GONE;
    }
    try {
      var uri = URI.create(endpoint);
      var now = clock.instant();
      var headers = new LinkedHashMap<String, String>();
      headers.put("TTL", Long.toString(ttl.toSeconds()));
      headers.put("Content-Encoding", "aes128gcm");
      headers.put("Content-Type", "application/octet-stream");
      headers.put("Urgency", "normal");
      headers.put(
          "Authorization",
          WebPushCrypto.vapidAuthorization(keys, uri, subject, now, now.plus(JWT_LIFETIME)));
      int status = transport.post(uri, headers, crypto.encrypt(p256dh, auth, payload));
      if (status >= 200 && status < 300) return Result.SENT;
      if (status == 404 || status == 410) return Result.GONE;
      log.warn("시세 알림 발송에 실패했습니다. service={}, status={}", service.get(), status);
      return Result.FAILED;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.warn("시세 알림 발송이 중단됐습니다. service={}", service.get());
      return Result.FAILED;
    } catch (IOException | RuntimeException exception) {
      // 예외 메시지에 주소가 들어 있을 수 있어 종류만 남긴다.
      log.warn(
          "시세 알림 발송에 실패했습니다. service={}, exceptionType={}",
          service.get(),
          exception.getClass().getName());
      return Result.FAILED;
    }
  }
}
