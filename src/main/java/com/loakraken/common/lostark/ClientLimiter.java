package com.loakraken.common.lostark;

import com.loakraken.common.api.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 클라이언트별 유입 제한(#108). 1분 고정 창 카운터를 버킷마다 센다.
 *
 * <ul>
 *   <li>요청 버킷(GENERAL·STATS·BOT)은 {@link ClientLimitFilter}가 요청마다 센다.
 *   <li>OFFICIAL은 요청이 실제로 일으킨 공식 API 호출을 {@link LostArkRequestGate#acquire()}에서 센다. 캐시 적중이나 다른 요청이
 *       시작한 단일 비행에 합류한 요청은 공식 호출이 없으므로 세지 않는다. 수집기·예열처럼 클라이언트가 없는 호출도 세지 않는다.
 * </ul>
 *
 * 클라이언트 키는 정규화한 주소({@link com.loakraken.common.api.ClientAddresses})의 HMAC-SHA256 앞 16바이트다. HMAC 키는
 * 공식 API 키에서 파생해 같은 키를 쓰는 인스턴스끼리 같은 키를 만들고, 주소 원문은 Valkey에 남기지 않는다. 저장소 장애면 제한하지 않는다(공식 호출 자체는 공유
 * 게이트가 지킨다).
 */
public class ClientLimiter {
  static final String PREFIX = "lostark:client-limit:";
  private static final ThreadLocal<String> CLIENT = new ThreadLocal<>();
  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final String BOT_PREFIX = "bot:";
  private static final String BOT_ALL = "all";

  /** 제한을 끈 상태. 단위 테스트용 게이트가 쓴다. */
  public static final ClientLimiter DISABLED =
      new ClientLimiter(
          new ClientLimitProperties(false, 0, 0, 0, 0, true, 0, 0, 0, 0),
          new ClientLimitStore.Memory(),
          Clock.systemUTC(),
          null,
          "");

  private final ClientLimitProperties properties;
  private final ClientLimitStore store;
  private final Clock clock;
  private final MeterRegistry registry;
  private final SecretKeySpec key;

  ClientLimiter(
      ClientLimitProperties properties,
      ClientLimitStore store,
      Clock clock,
      MeterRegistry registry,
      String apiKey) {
    this.properties = properties;
    this.store = store;
    this.clock = clock;
    this.registry = registry;
    this.key = new SecretKeySpec(keyMaterial(apiKey), "HmacSHA256");
  }

  public enum Bucket {
    OFFICIAL,
    STATS,
    GENERAL,
    BOT,
    ROSTER_REFRESH,
    AUCTION_SEARCH,
    BOT_OFFICIAL,
    BOT_ADDRESS_OFFICIAL
  }

  /** 판정 결과. 거절이면 retryAfterSeconds는 지금 창이 끝날 때까지 남은 초(1 이상)다. */
  public record Decision(boolean allowed, long retryAfterSeconds) {
    static final Decision ALLOW = new Decision(true, 0);
  }

  boolean enabled() {
    return properties.enabled();
  }

  boolean exemptInternal() {
    return properties.exemptInternal();
  }

  /** 정규화한 주소를 클라이언트 키로 바꾼다. */
  String clientKey(String normalizedAddress) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      var digest = mac.doFinal(normalizedAddress.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 16);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** 버킷 카운터를 올리고 판정한다. clientKey는 {@link #clientKey}·{@link #botKey} 값이거나, 전체 상한용 고정 키다. */
  Decision check(Bucket bucket, String clientKey) {
    if (!properties.enabled()) return Decision.ALLOW;
    long now = clock.millis();
    long window = now / WINDOW.toMillis();
    long count;
    try {
      count =
          store.increment(
              PREFIX + bucket.name().toLowerCase(Locale.ROOT) + ":" + clientKey + ":" + window,
              WINDOW.plusSeconds(1));
    } catch (ClientLimitStore.Unavailable exception) {
      count(bucket, "unavailable");
      return Decision.ALLOW;
    }
    if (count <= properties.limit(bucket)) return Decision.ALLOW;
    count(bucket, "reject");
    long remaining = (window + 1) * WINDOW.toMillis() - now;
    return new Decision(false, Math.max(1, (remaining + 999) / 1000));
  }

  /** 요청을 처리하는 동안 이 스레드의 공식 호출을 clientKey에 매긴다. */
  void enter(String clientKey) {
    CLIENT.set(clientKey);
  }

  void exit() {
    CLIENT.remove();
  }

  /**
   * 지금 스레드의 요청이 공식 호출을 하나 더 해도 되는지 센다. 클라이언트가 없으면(백그라운드) 세지 않는다. 검색봇 표시 요청은 OFFICIAL 대신 더 작은 주소별
   * BOT_ADDRESS_OFFICIAL과 모든 봇이 함께 쓰는 BOT_OFFICIAL 상한을 센다(한 주소가 전체 상한을 다 쓰지 못하게).
   */
  void chargeOfficial() {
    var client = CLIENT.get();
    if (client == null) return;
    boolean bot = bot(client);
    if (!check(bot ? Bucket.BOT_ADDRESS_OFFICIAL : Bucket.OFFICIAL, client).allowed()
        || (bot && !check(Bucket.BOT_OFFICIAL, BOT_ALL).allowed()))
      throw new BusinessException(LostArkErrorCode.CLIENT_RATE_LIMITED);
  }

  /** 검색봇 표시 요청의 클라이언트 키(주소별). */
  String botKey(String normalizedAddress) {
    return BOT_PREFIX + clientKey(normalizedAddress);
  }

  static boolean bot(String client) {
    return client.startsWith(BOT_PREFIX);
  }

  /**
   * 지금 스레드의 요청을 버킷에 한 번 센다(서비스가 직접 세는 버킷, 예: 경매 검색기 캐시 미스). 넘으면 CLIENT_RATE_LIMITED와 Retry-After.
   * 클라이언트가 없으면(백그라운드·내부 주소) 세지 않는다.
   */
  void charge(Bucket bucket) {
    var client = CLIENT.get();
    if (client == null) return;
    var decision = check(bucket, client);
    if (!decision.allowed())
      throw new BusinessException(
          LostArkErrorCode.CLIENT_RATE_LIMITED, Duration.ofSeconds(decision.retryAfterSeconds()));
  }

  void count(Bucket bucket, String event) {
    if (registry == null) return;
    registry
        .counter(
            "loakraken.client.limit",
            "bucket",
            bucket.name().toLowerCase(Locale.ROOT),
            "event",
            event)
        .increment();
  }

  void unattributed() {
    if (registry != null) registry.counter("loakraken.client.limit.unattributed").increment();
  }

  private static byte[] keyMaterial(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      var random = new byte[32];
      new SecureRandom().nextBytes(random);
      return random;
    }
    return ("loakraken-client-limit:" + apiKey).getBytes(StandardCharsets.UTF_8);
  }
}
