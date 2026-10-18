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

  /** 필터가 둔 경로 힌트('POST /api/v1/자원명/…'). 컨트롤러 경로 템플릿이 아직 없는 필터 단계 거절 로그에 쓴다. */
  private static final ThreadLocal<String> ROUTE = new ThreadLocal<>();

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final String BOT_PREFIX = "bot:";
  private static final String BOT_ALL = "all";
  private static final String SHARED = "shared";

  /** 거절 로그는 버킷·클라이언트·분마다 처음 이만큼만(지표는 전부 센다) — 한 클라이언트가 로그를 넘치게 하지 못하게. */
  static final int REJECT_LOGS_PER_WINDOW = 5;

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ClientLimiter.class);

  /** 로그 경로 힌트에 그대로 남기는 자원명(/api/v1/ 다음 첫 칸). 그 밖은 '*' — 모르는 경로의 값이 로그에 남지 않게(#616 코덱스 P2). */
  static final java.util.Set<String> ROUTE_RESOURCES =
      java.util.Set.of(
          "alerts",
          "characters",
          "collectibles",
          "gems",
          "guilds",
          "home",
          "markets",
          "rankings",
          "refining",
          "stats",
          "titles");

  /** 제한을 끈 상태. 단위 테스트용 게이트가 쓴다. */
  public static final ClientLimiter DISABLED =
      new ClientLimiter(
          new ClientLimitProperties(false, 0, 0, 0, 0, true, 0, 0, 0, 0, 5),
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
    BOT_ADDRESS_OFFICIAL,
    ALERT_SUBSCRIBE,
    MATERIAL_PRICES,
    GEM_FILTER,
    MATERIAL_SEARCH,
    MATERIAL_SEARCH_ALL
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
      count = store.increment(storeKey(bucket, clientKey, window), WINDOW.plusSeconds(1));
    } catch (ClientLimitStore.Unavailable exception) {
      count(bucket, "unavailable");
      return Decision.ALLOW;
    }
    if (count <= properties.limit(bucket)) return Decision.ALLOW;
    count(bucket, "reject");
    if (count <= properties.limit(bucket) + REJECT_LOGS_PER_WINDOW) logReject(bucket, clientKey);
    long remaining = (window + 1) * WINDOW.toMillis() - now;
    return new Decision(false, Math.max(1, (remaining + 999) / 1000));
  }

  /** 주소·캐릭터 이름은 남기지 않는다: 경로는 템플릿(값 없음) 또는 자원명까지, 클라이언트는 HMAC 키 앞 6자리(#616). */
  private static void logReject(Bucket bucket, String clientKey) {
    log.info(
        "event=client_limit_reject bucket={} route={} client={}",
        bucket.name().toLowerCase(Locale.ROOT),
        route(),
        shortKey(clientKey));
  }

  private static String storeKey(Bucket bucket, String clientKey, long window) {
    return PREFIX + bucket.name().toLowerCase(Locale.ROOT) + ":" + clientKey + ":" + window;
  }

  /**
   * 지금 스레드의 클라이언트가 이번 창에서 공식 호출을 몇 번 더 할 수 있는지(올리지 않고 읽기만). 세지 않는 경로(한도 꺼짐·클라이언트 없음)와 저장소 장애는
   * {@link Integer#MAX_VALUE} — 그때는 {@link #chargeOfficial}도 막지 않으므로 같게 둔다.
   */
  int remainingOfficial() {
    if (!properties.enabled()) return Integer.MAX_VALUE;
    var client = CLIENT.get();
    if (client == null) return Integer.MAX_VALUE;
    var bucket = bot(client) ? Bucket.BOT_ADDRESS_OFFICIAL : Bucket.OFFICIAL;
    long window = clock.millis() / WINDOW.toMillis();
    try {
      long used = store.current(storeKey(bucket, client, window));
      return (int) Math.max(0, properties.limit(bucket) - used);
    } catch (ClientLimitStore.Unavailable exception) {
      return Integer.MAX_VALUE;
    }
  }

  /** 로그용 클라이언트 표시: HMAC 키 앞 6자리(봇은 'bot:' + 앞 6자리, 봇 전체 상한은 'bot-all'). */
  static String shortKey(String clientKey) {
    if (clientKey == null) return "-";
    if (BOT_ALL.equals(clientKey)) return "bot-all";
    if (SHARED.equals(clientKey)) return "shared";
    if (clientKey.startsWith(BOT_PREFIX))
      return BOT_PREFIX + head(clientKey.substring(BOT_PREFIX.length()));
    return head(clientKey);
  }

  private static String head(String hex) {
    return hex.length() <= 6 ? hex : hex.substring(0, 6);
  }

  /**
   * 로그용 경로. 컨트롤러가 정해졌으면 그 경로 템플릿(예: GET /api/v1/guilds/{server}/{guildName} — 실제 이름 없음), 아니면 필터가 둔
   * 힌트, 둘 다 없으면 '-'(백그라운드).
   */
  static String route() {
    if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
        instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
      var request = attributes.getRequest();
      var pattern =
          request.getAttribute(
              org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      if (pattern instanceof String template) return request.getMethod() + " " + template;
    }
    var hint = ROUTE.get();
    return hint == null ? "-" : hint;
  }

  /**
   * 경로 힌트: '/api/v1/자원명'까지만 남기고 뒤는 '…'(캐릭터·길드 이름이 들어갈 수 있어서). 자원명이 알려진 목록({@link
   * #ROUTE_RESOURCES})에 없으면 '*', '/api/v1/' 모양이 아니면 '*'.
   */
  static String routeHint(String method, String path) {
    var parts = path.split("/", -1);
    if (parts.length < 4 || !"api".equals(parts[1]) || !"v1".equals(parts[2])) return method + " *";
    var resource = ROUTE_RESOURCES.contains(parts[3]) ? parts[3] : "*";
    return method
        + " /"
        + parts[1]
        + "/"
        + parts[2]
        + "/"
        + resource
        + (parts.length > 4 ? "/…" : "");
  }

  void enterRoute(String method, String path) {
    ROUTE.set(routeHint(method, path));
  }

  void exitRoute() {
    ROUTE.remove();
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

  /**
   * 지금 클라이언트를 버킷에 한 번 세고, 되돌릴 때 쓸 카운터 키를 돌려준다(세지 않았으면 null — 클라이언트 없음·제한 꺼짐·저장소 장애). 넘으면 센 것을 바로
   * 되돌리고 CLIENT_RATE_LIMITED(카운터가 한도 위로 부풀지 않게).
   */
  String chargeRefundable(Bucket bucket) {
    var client = CLIENT.get();
    if (client == null) return null;
    return countRefundable(bucket, client, LostArkErrorCode.CLIENT_RATE_LIMITED);
  }

  /**
   * 모든 클라이언트가 함께 쓰는 기능 상한(예: 재료 검색 전체 분당 36)을 한 번 세고 되돌리기용 키를 돌려준다. 클라이언트가 없어도(내부 주소) 센다. 넘으면 센 것을
   * 되돌리고 LOST_ARK_RATE_LIMITED(공유 예산이 막힌 것과 같은 거절 — 부르는 쪽은 지금 알 수 없음으로 처리).
   */
  String chargeShared(Bucket bucket) {
    return countRefundable(bucket, SHARED, LostArkErrorCode.LOST_ARK_RATE_LIMITED);
  }

  /**
   * {@link #chargeRefundable}·{@link #chargeShared}로 센 것을 되돌린다(공식 호출 전에 게이트가 거절했을 때). null이면 아무것도
   * 하지 않는다.
   */
  void refund(String counterKey) {
    if (counterKey != null) store.decrement(counterKey);
  }

  private String countRefundable(Bucket bucket, String clientKey, LostArkErrorCode rejected) {
    if (!properties.enabled()) return null;
    long now = clock.millis();
    long window = now / WINDOW.toMillis();
    String key = storeKey(bucket, clientKey, window);
    long count;
    try {
      count = store.increment(key, WINDOW.plusSeconds(1));
    } catch (ClientLimitStore.Unavailable exception) {
      count(bucket, "unavailable");
      return null;
    }
    if (count <= properties.limit(bucket)) return key;
    store.decrement(key);
    count(bucket, "reject");
    // 되돌려서 카운터가 한도 + 1에 머무르므로, 거절 로그 상한은 따로 센다(같은 5건, #616 코덱스 P2).
    try {
      if (store.increment(
              PREFIX + "reject-log:" + key.substring(PREFIX.length()), WINDOW.plusSeconds(1))
          <= REJECT_LOGS_PER_WINDOW) logReject(bucket, clientKey);
    } catch (ClientLimitStore.Unavailable exception) {
      // 로그 상한을 셀 수 없으면 로그를 남기지 않는다(거절은 그대로).
    }
    long remaining = (window + 1) * WINDOW.toMillis() - now;
    throw rejected == LostArkErrorCode.CLIENT_RATE_LIMITED
        ? new BusinessException(rejected, Duration.ofSeconds(Math.max(1, (remaining + 999) / 1000)))
        : new BusinessException(rejected);
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
