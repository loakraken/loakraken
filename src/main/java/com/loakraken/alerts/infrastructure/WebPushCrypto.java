package com.loakraken.alerts.infrastructure;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 웹 푸시 암호(M33). JDK 표준 API만 쓴다. 암호 코드는 이 클래스에만 둔다.
 *
 * <ul>
 *   <li>메시지 암호화: RFC 8291(aes128gcm). 메시지마다 새 임시 ECDH P-256 키와 16바이트 무작위 salt를 만든다. 레코드 크기 rs =
 *       4096, keyid = 임시 공개 키(비압축 65바이트), 단일 레코드라 패딩 구분자는 0x02다.
 *   <li>VAPID: RFC 8292. ES256 JWT(aud = 푸시 서비스의 scheme+host, exp ≤ 12시간, sub = 설정값). JDK 서명은 DER이라
 *       JOSE 형식(R‖S 64바이트)으로 바꾼다.
 * </ul>
 *
 * 키·salt·평문·결과를 로그나 예외 메시지에 넣지 않는다.
 */
public final class WebPushCrypto {
  static final int RECORD_SIZE = 4096;
  private static final int SALT_BYTES = 16;
  private static final int AUTH_BYTES = 16;
  private static final int POINT_BYTES = 65;
  private static final byte[] KEY_INFO = ascii("WebPush: info\0");
  private static final byte[] CEK_INFO = ascii("Content-Encoding: aes128gcm\0");
  private static final byte[] NONCE_INFO = ascii("Content-Encoding: nonce\0");
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
  private static final ECParameterSpec P256 = p256();

  private final SecureRandom random;

  public WebPushCrypto(SecureRandom random) {
    this.random = random;
  }

  /** 잘못된 키·형식. 메시지에 키나 값을 넣지 않는다. */
  public static final class CryptoException extends RuntimeException {
    CryptoException(String reason) {
      super(reason);
    }
  }

  /** VAPID 키 한 쌍(설정의 base64url 공개 키 65바이트·개인 키 32바이트). */
  public record VapidKeys(ECPublicKey publicKey, ECPrivateKey privateKey, String publicKeyText) {
    @Override
    public String toString() {
      return "VapidKeys[***]";
    }
  }

  public static VapidKeys vapidKeys(String publicKeyText, String privateKeyText) {
    var publicKey = publicKey(decode(publicKeyText, POINT_BYTES, "VAPID 공개 키"));
    var d = decode(privateKeyText, 32, "VAPID 개인 키");
    try {
      var privateKey =
          (ECPrivateKey)
              KeyFactory.getInstance("EC")
                  .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
      // 두 키가 한 쌍인지 확인한다(서명 → 검증).
      var probe = ascii("vapid-key-check");
      if (!verifyJose(publicKey, probe, signJose(privateKey, probe)))
        throw new CryptoException("VAPID 키가 한 쌍이 아닙니다.");
      return new VapidKeys(publicKey, privateKey, publicKeyText);
    } catch (GeneralSecurityException exception) {
      throw new CryptoException("VAPID 개인 키를 읽을 수 없습니다.");
    }
  }

  /** 받는 쪽(브라우저) 구독 키를 확인한다. p256dh는 비압축 P-256 점 65바이트, auth는 16바이트다. */
  public static void validateSubscriptionKeys(String p256dh, String auth) {
    publicKey(decode(p256dh, POINT_BYTES, "p256dh"));
    decode(auth, AUTH_BYTES, "auth");
  }

  /** 메시지 하나를 암호화한다. 호출마다 새 임시 키와 salt를 쓴다. */
  public byte[] encrypt(String p256dh, String auth, byte[] plaintext) {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"), random);
      var ephemeral = generator.generateKeyPair();
      var salt = new byte[SALT_BYTES];
      random.nextBytes(salt);
      return encrypt(
          decode(p256dh, POINT_BYTES, "p256dh"),
          decode(auth, AUTH_BYTES, "auth"),
          plaintext,
          ephemeral,
          salt);
    } catch (GeneralSecurityException exception) {
      throw new CryptoException("메시지를 암호화할 수 없습니다.");
    }
  }

  /** RFC 8291 3·4절. 테스트 벡터 확인을 위해 임시 키·salt를 받는다. */
  static byte[] encrypt(
      byte[] uaPublic, byte[] authSecret, byte[] plaintext, KeyPair ephemeral, byte[] salt) {
    if (plaintext.length + 1 + 16 > RECORD_SIZE) throw new CryptoException("알림 내용이 한 레코드보다 깁니다.");
    try {
      var asPublic = point((ECPublicKey) ephemeral.getPublic());
      var agreement = KeyAgreement.getInstance("ECDH");
      agreement.init(ephemeral.getPrivate());
      agreement.doPhase(publicKey(uaPublic), true);
      var ecdhSecret = agreement.generateSecret();

      var prkKey = hmac(authSecret, ecdhSecret);
      var keyInfo = concat(KEY_INFO, uaPublic, asPublic);
      var ikm = expand(prkKey, keyInfo, 32);
      var prk = hmac(salt, ikm);
      var cek = expand(prk, CEK_INFO, 16);
      var nonce = expand(prk, NONCE_INFO, 12);

      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
      var ciphertext = cipher.doFinal(concat(plaintext, new byte[] {0x02}));

      var header =
          ByteBuffer.allocate(SALT_BYTES + 4 + 1 + POINT_BYTES)
              .put(salt)
              .putInt(RECORD_SIZE)
              .put((byte) POINT_BYTES)
              .put(asPublic)
              .array();
      return concat(header, ciphertext);
    } catch (GeneralSecurityException exception) {
      throw new CryptoException("메시지를 암호화할 수 없습니다.");
    }
  }

  /**
   * VAPID Authorization 헤더 값: {@code vapid t=<JWT>, k=<공개 키>}. aud는 endpoint의 scheme+host(+기본이 아닌
   * 포트)다.
   *
   * @param expiresAt 12시간 이내여야 한다
   */
  public static String vapidAuthorization(
      VapidKeys keys, URI endpoint, String subject, Instant now, Instant expiresAt) {
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(12 * 3600)))
      throw new CryptoException("VAPID 만료 시각은 12시간 이내여야 합니다.");
    var audience =
        endpoint.getScheme()
            + "://"
            + endpoint.getHost()
            + (endpoint.getPort() == -1 || endpoint.getPort() == 443
                ? ""
                : ":" + endpoint.getPort());
    var header = URL.encodeToString(ascii("{\"typ\":\"JWT\",\"alg\":\"ES256\"}"));
    var payload =
        URL.encodeToString(
            ("{\"aud\":\""
                    + json(audience)
                    + "\",\"exp\":"
                    + expiresAt.getEpochSecond()
                    + ",\"sub\":\""
                    + json(subject)
                    + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    var signingInput = header + "." + payload;
    try {
      var signature = signJose(keys.privateKey(), ascii(signingInput));
      return "vapid t="
          + signingInput
          + "."
          + URL.encodeToString(signature)
          + ", k="
          + keys.publicKeyText();
    } catch (GeneralSecurityException exception) {
      throw new CryptoException("VAPID 서명을 만들 수 없습니다.");
    }
  }

  /** ES256 서명을 JOSE 형식(R‖S 각 32바이트)으로 만든다. */
  static byte[] signJose(ECPrivateKey key, byte[] input) throws GeneralSecurityException {
    var signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key);
    signer.update(input);
    return derToJose(signer.sign());
  }

  static boolean verifyJose(ECPublicKey key, byte[] input, byte[] jose)
      throws GeneralSecurityException {
    var verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(key);
    verifier.update(input);
    return verifier.verify(joseToDer(jose));
  }

  /** DER SEQUENCE { INTEGER r, INTEGER s } → r(32)‖s(32). */
  static byte[] derToJose(byte[] der) {
    int offset = 2;
    if ((der[1] & 0xff) == 0x81) offset = 3;
    if (der[0] != 0x30 || der[offset] != 0x02) throw new CryptoException("서명 형식이 올바르지 않습니다.");
    int rLength = der[offset + 1];
    var r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
    int sOffset = offset + 2 + rLength;
    if (der[sOffset] != 0x02) throw new CryptoException("서명 형식이 올바르지 않습니다.");
    int sLength = der[sOffset + 1];
    var s = Arrays.copyOfRange(der, sOffset + 2, sOffset + 2 + sLength);
    return concat(fixed32(r), fixed32(s));
  }

  static byte[] joseToDer(byte[] jose) {
    if (jose.length != 64) throw new CryptoException("서명 길이가 올바르지 않습니다.");
    var r = new BigInteger(1, Arrays.copyOfRange(jose, 0, 32)).toByteArray();
    var s = new BigInteger(1, Arrays.copyOfRange(jose, 32, 64)).toByteArray();
    var body = new ByteArrayOutputStream();
    body.write(0x02);
    body.write(r.length);
    body.writeBytes(r);
    body.write(0x02);
    body.write(s.length);
    body.writeBytes(s);
    var content = body.toByteArray();
    var out = new ByteArrayOutputStream();
    out.write(0x30);
    if (content.length > 127) out.write(0x81);
    out.write(content.length);
    out.writeBytes(content);
    return out.toByteArray();
  }

  private static byte[] fixed32(byte[] value) {
    var unsigned = new BigInteger(1, value).toByteArray();
    if (unsigned.length > 33 || (unsigned.length == 33 && unsigned[0] != 0))
      throw new CryptoException("서명 형식이 올바르지 않습니다.");
    var result = new byte[32];
    int from = Math.max(0, unsigned.length - 32);
    int length = unsigned.length - from;
    System.arraycopy(unsigned, from, result, 32 - length, length);
    return result;
  }

  static ECPublicKey publicKey(byte[] uncompressed) {
    if (uncompressed.length != POINT_BYTES || uncompressed[0] != 0x04)
      throw new CryptoException("공개 키 형식이 올바르지 않습니다.");
    var x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    var y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    // JDK KeyFactory는 점이 곡선 위에 있는지 확인하지 않는다. 곡선 밖 점(잘못된 곡선 공격)을 ECDH에 넣지 않게 직접 확인한다.
    if (!onCurve(x, y)) throw new CryptoException("공개 키가 P-256 곡선 위의 점이 아닙니다.");
    try {
      return (ECPublicKey)
          KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new CryptoException("공개 키가 P-256 곡선 위의 점이 아닙니다.");
    }
  }

  /** P-256: 0 ≤ x, y < p 이고 y² ≡ x³ + ax + b (mod p). (P-256은 여인수가 1이라 곡선 위 유한 점이면 올바른 부분군이다.) */
  static boolean onCurve(BigInteger x, BigInteger y) {
    var field = (java.security.spec.ECFieldFp) P256.getCurve().getField();
    var p = field.getP();
    if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0)
      return false;
    var a = P256.getCurve().getA();
    var b = P256.getCurve().getB();
    var left = y.multiply(y).mod(p);
    var right = x.pow(3).add(a.multiply(x)).add(b).mod(p);
    return left.equals(right);
  }

  static byte[] point(ECPublicKey key) {
    var result = new byte[POINT_BYTES];
    result[0] = 0x04;
    var x = fixed32(key.getW().getAffineX().toByteArray());
    var y = fixed32(key.getW().getAffineY().toByteArray());
    System.arraycopy(x, 0, result, 1, 32);
    System.arraycopy(y, 0, result, 33, 32);
    return result;
  }

  static KeyPair keyPair(byte[] privateKey, byte[] publicKey) throws GeneralSecurityException {
    var priv =
        KeyFactory.getInstance("EC")
            .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, privateKey), P256));
    return new KeyPair(publicKey(publicKey), priv);
  }

  private static byte[] decode(String text, int length, String name) {
    byte[] value;
    try {
      value = URL_DECODER.decode(text == null ? "" : text.strip());
    } catch (IllegalArgumentException exception) {
      throw new CryptoException(name + " 형식이 올바르지 않습니다.");
    }
    if (value.length != length) throw new CryptoException(name + " 길이가 올바르지 않습니다.");
    return value;
  }

  /** HKDF-Expand(첫 블록만): HMAC(prk, info ‖ 0x01)의 앞 length바이트. length ≤ 32. */
  private static byte[] expand(byte[] prk, byte[] info, int length)
      throws GeneralSecurityException {
    return Arrays.copyOf(hmac(prk, concat(info, new byte[] {0x01})), length);
  }

  private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  private static byte[] concat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (var part : parts) out.writeBytes(part);
    return out.toByteArray();
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String json(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static ECParameterSpec p256() {
    try {
      var parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("P-256 곡선을 쓸 수 없습니다.", exception);
    }
  }
}
