package com.loakraken.alerts.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** 웹 푸시 암호(M33): RFC 8291 부록 A 벡터, 메시지마다 새 키·salt, VAPID ES256(JOSE R‖S 64바이트). */
class WebPushCryptoTest {
  private static final Base64.Decoder URL = Base64.getUrlDecoder();
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

  // RFC 8291 부록 A(https://www.rfc-editor.org/rfc/rfc8291, 2026-10-13 확인).
  private static final String PLAINTEXT = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24";
  private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
  private static final String AS_PUBLIC =
      "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  private static final String UA_PUBLIC =
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
  private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
  private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
  private static final String BODY =
      "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
          + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
          + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

  @Test
  void RFC_8291_부록_A의_암호문과_바이트가_같다() throws Exception {
    var ephemeral = WebPushCrypto.keyPair(URL.decode(AS_PRIVATE), URL.decode(AS_PUBLIC));
    var body =
        WebPushCrypto.encrypt(
            URL.decode(UA_PUBLIC),
            URL.decode(AUTH_SECRET),
            URL.decode(PLAINTEXT),
            ephemeral,
            URL.decode(SALT));
    assertThat(body).isEqualTo(URL.decode(BODY));
    // 헤더: salt 16 · rs 4096 · keyid 길이 65 · 임시 공개 키.
    assertThat(Arrays.copyOfRange(body, 16, 20)).containsExactly(0, 0, 0x10, 0);
    assertThat(body[20]).isEqualTo((byte) 65);
    assertThat(Arrays.copyOfRange(body, 21, 86)).isEqualTo(URL.decode(AS_PUBLIC));
  }

  @Test
  void 메시지마다_새_임시_키와_salt를_쓴다() throws Exception {
    var crypto = new WebPushCrypto(new SecureRandom());
    var plaintext = "{\"itemId\":1}".getBytes(StandardCharsets.UTF_8);
    var first = crypto.encrypt(UA_PUBLIC, AUTH_SECRET, plaintext);
    var second = crypto.encrypt(UA_PUBLIC, AUTH_SECRET, plaintext);
    assertThat(Arrays.copyOfRange(first, 0, 16)).isNotEqualTo(Arrays.copyOfRange(second, 0, 16));
    assertThat(Arrays.copyOfRange(first, 21, 86)).isNotEqualTo(Arrays.copyOfRange(second, 21, 86));
    assertThat(first).isNotEqualTo(second);
    // 단일 레코드: 헤더 86 + 평문 + 구분자 1 + 태그 16.
    assertThat(first).hasSize(86 + plaintext.length + 1 + 16);
  }

  @Test
  void 잘못된_구독_키는_값을_드러내지_않고_거절한다() {
    assertThatThrownBy(() -> WebPushCrypto.validateSubscriptionKeys("abc", AUTH_SECRET))
        .isInstanceOf(WebPushCrypto.CryptoException.class)
        .hasMessageNotContaining("abc");
    // 곡선 위의 점이 아닌 65바이트
    var notOnCurve = new byte[65];
    notOnCurve[0] = 0x04;
    notOnCurve[64] = 1;
    assertThatThrownBy(
            () ->
                WebPushCrypto.validateSubscriptionKeys(
                    URL_ENCODER.encodeToString(notOnCurve), AUTH_SECRET))
        .isInstanceOf(WebPushCrypto.CryptoException.class);
    assertThatThrownBy(() -> WebPushCrypto.validateSubscriptionKeys(UA_PUBLIC, "AAAA"))
        .isInstanceOf(WebPushCrypto.CryptoException.class);
    // 올바른 점 하나를 1비트 바꾸면 곡선 밖이다(JDK KeyFactory만으로는 걸러지지 않아 직접 확인).
    var tampered = URL.decode(UA_PUBLIC);
    tampered[64] ^= 1;
    assertThatThrownBy(
            () ->
                WebPushCrypto.validateSubscriptionKeys(
                    URL_ENCODER.encodeToString(tampered), AUTH_SECRET))
        .isInstanceOf(WebPushCrypto.CryptoException.class);
    // 좌표가 p 이상이면 거절.
    var big = new byte[65];
    big[0] = 0x04;
    Arrays.fill(big, 1, 65, (byte) 0xff);
    assertThatThrownBy(
            () ->
                WebPushCrypto.validateSubscriptionKeys(
                    URL_ENCODER.encodeToString(big), AUTH_SECRET))
        .isInstanceOf(WebPushCrypto.CryptoException.class);
    // 올바른 키는 통과한다.
    WebPushCrypto.validateSubscriptionKeys(UA_PUBLIC, AUTH_SECRET);
  }

  @Test
  void VAPID는_ES256_JOSE_64바이트_서명이고_공개_키로_검증된다() throws Exception {
    var keys = keys();
    var now = Instant.parse("2026-10-13T15:00:00Z");
    var header =
        WebPushCrypto.vapidAuthorization(
            keys,
            URI.create("https://fcm.googleapis.com/fcm/send/abc"),
            "mailto:ops@example.com",
            now,
            now.plusSeconds(12 * 3600 - 300));
    assertThat(header).startsWith("vapid t=").contains(", k=" + keys.publicKeyText());
    var jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
    var parts = jwt.split("\\.");
    assertThat(parts).hasSize(3);
    assertThat(new String(URL.decode(parts[0]), StandardCharsets.UTF_8))
        .isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
    var payload = new String(URL.decode(parts[1]), StandardCharsets.UTF_8);
    assertThat(payload)
        .isEqualTo(
            "{\"aud\":\"https://fcm.googleapis.com\",\"exp\":"
                + (now.getEpochSecond() + 12 * 3600 - 300)
                + ",\"sub\":\"mailto:ops@example.com\"}");
    var signature = URL.decode(parts[2]);
    assertThat(signature).hasSize(64);
    assertThat(
            WebPushCrypto.verifyJose(
                keys.publicKey(),
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                signature))
        .isTrue();
    // 12시간을 넘는 만료는 거절한다.
    assertThatThrownBy(
            () ->
                WebPushCrypto.vapidAuthorization(
                    keys,
                    URI.create("https://fcm.googleapis.com/x"),
                    "mailto:ops@example.com",
                    now,
                    now.plusSeconds(12 * 3600 + 1)))
        .isInstanceOf(WebPushCrypto.CryptoException.class);
  }

  @Test
  void DER와_JOSE_변환은_앞자리_0인_서명도_왕복한다() throws Exception {
    var keys = keys();
    for (int i = 0; i < 300; i++) {
      var input = ("message-" + i).getBytes(StandardCharsets.UTF_8);
      var jose = WebPushCrypto.signJose(keys.privateKey(), input);
      assertThat(jose).hasSize(64);
      assertThat(WebPushCrypto.verifyJose(keys.publicKey(), input, jose)).isTrue();
    }
  }

  @Test
  void VAPID_키_쌍이_맞지_않으면_거절한다() throws Exception {
    var a = keys();
    var b = keys();
    var privateB = URL_ENCODER.encodeToString(scalar((ECPrivateKey) b.privateKey()));
    assertThatThrownBy(() -> WebPushCrypto.vapidKeys(a.publicKeyText(), privateB))
        .isInstanceOf(WebPushCrypto.CryptoException.class);
    assertThat(a.toString()).doesNotContain(a.publicKeyText());
  }

  /** 시험용 VAPID 키(공개 65바이트·개인 32바이트 base64url). */
  static WebPushCrypto.VapidKeys keys() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var pair = generator.generateKeyPair();
    var publicText =
        URL_ENCODER.encodeToString(WebPushCrypto.point((ECPublicKey) pair.getPublic()));
    var privateText = URL_ENCODER.encodeToString(scalar((ECPrivateKey) pair.getPrivate()));
    return WebPushCrypto.vapidKeys(publicText, privateText);
  }

  static byte[] scalar(ECPrivateKey key) {
    var raw = key.getS().toByteArray();
    var result = new byte[32];
    int from = Math.max(0, raw.length - 32);
    System.arraycopy(raw, from, result, 32 - (raw.length - from), raw.length - from);
    return result;
  }
}
