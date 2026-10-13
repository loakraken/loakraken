package com.loakraken.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 구독 주소 허용 목록(M33). */
class PushEndpointPolicyTest {
  @ParameterizedTest
  @CsvSource({
    "https://fcm.googleapis.com/fcm/send/abc,FCM",
    "https://updates.push.services.mozilla.com/wpush/v2/abc,MOZILLA",
    "https://web.push.apple.com/QK2xyz,APPLE",
    "https://wns2-par02p.notify.windows.com/w/?token=abc,WINDOWS",
    "https://FCM.GOOGLEAPIS.COM/fcm/send/abc,FCM",
    "https://fcm.googleapis.com:443/fcm/send/abc,FCM"
  })
  void 브라우저_푸시_서비스는_받는다(String endpoint, PushEndpointPolicy.Service service) {
    assertThat(PushEndpointPolicy.check(endpoint)).contains(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://fcm.googleapis.com/fcm/send/abc",
        "https://fcm.googleapis.com:8443/fcm/send/abc",
        "https://user@fcm.googleapis.com/fcm/send/abc",
        "https://fcm.googleapis.com.example/fcm/send/abc",
        "https://evil-fcm.googleapis.com/fcm/send/abc",
        "https://evilnotify.windows.com/w/",
        "https://evilnotify.windows.com.example/w/",
        "https://notify.windows.com/w/",
        "https://fcm.googleapis.com./fcm/send/abc",
        "https://127.0.0.1/push",
        "https://[::1]/push",
        "https://localhost/push",
        "https://fcm.googleapis.com/fcm/send/abc#frag",
        "ftp://fcm.googleapis.com/x",
        "not a url",
        ""
      })
  void 그_밖의_주소는_거절한다(String endpoint) {
    assertThat(PushEndpointPolicy.check(endpoint)).isEmpty();
  }

  @org.junit.jupiter.api.Test
  void 너무_긴_주소는_거절한다() {
    assertThat(PushEndpointPolicy.check("https://fcm.googleapis.com/" + "a".repeat(1024)))
        .isEmpty();
    assertThat(PushEndpointPolicy.check(null)).isEmpty();
  }
}
