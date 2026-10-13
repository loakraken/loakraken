package com.loakraken.alerts.application;

import com.loakraken.alerts.domain.AlertComparator;
import com.loakraken.alerts.domain.PushEndpointPolicy;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketPricesObserved;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 시세 알림 웹 푸시(M33). 구독은 로그인 없이 브라우저별로 만들고, 관리 토큰(무작위 256비트)을 한 번만 돌려준다. 저장은 토큰의 SHA-256 해시뿐이다.
 *
 * <p>조건은 예열 품목에만 건다. 만들 때 이미 참이면 보내지 않고(armed=false로 시작), 거짓이 됐다가 다시 참이 될 때 보낸다. 같은 조건은
 * resendInterval 안에 다시 보내지 않는다. 로그에는 구독 주소·키·토큰 원문을 남기지 않는다.
 */
public class AlertService {
  private static final Logger log = LoggerFactory.getLogger(AlertService.class);
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final Duration MINUTE = Duration.ofMinutes(1);

  private final AlertStore store;
  private final PushSender sender;
  private final AlertProperties properties;
  private final Clock clock;
  private final SecureRandom random;
  private final BiConsumer<String, String> subscriptionKeyCheck;
  private long sendWindow = -1;
  private int sentInWindow;

  /**
   * @param subscriptionKeyCheck 브라우저 구독 키(p256dh·auth)를 확인한다. 잘못되면 예외를 던진다(암호 코드는 인프라 한 곳에 둔다)
   */
  public AlertService(
      AlertStore store,
      PushSender sender,
      AlertProperties properties,
      Clock clock,
      SecureRandom random,
      BiConsumer<String, String> subscriptionKeyCheck) {
    this.store = store;
    this.sender = sender;
    this.properties = properties;
    this.clock = clock;
    this.random = random;
    this.subscriptionKeyCheck = subscriptionKeyCheck;
  }

  public record Subscription(UUID subscriptionId, String manageToken) {
    @Override
    public String toString() {
      return "Subscription[" + subscriptionId + "]";
    }
  }

  /** 같은 endpoint로 다시 구독하면 같은 구독에 새 키·새 토큰(옛 토큰 무효)을 두고 옛 조건은 지운다. */
  public Subscription subscribe(String endpoint, String p256dh, String auth) {
    if (PushEndpointPolicy.check(endpoint).isEmpty())
      throw new BusinessException(AlertErrorCode.ALERT_INVALID_SUBSCRIPTION);
    try {
      subscriptionKeyCheck.accept(p256dh, auth);
    } catch (RuntimeException exception) {
      throw new BusinessException(AlertErrorCode.ALERT_INVALID_SUBSCRIPTION);
    }
    var token = new byte[32];
    random.nextBytes(token);
    var tokenText = URL.encodeToString(token);
    var now = clock.instant();
    // 상한은 새 구독에만 건다(같은 endpoint 재구독은 수가 늘지 않음). 동시 요청으로 조금 넘을 수 있지만 상한은 남용 방지용이다.
    if (store.subscriptionCount() >= properties.maxSubscriptions()
        && !store.endpointExists(endpoint)) throw new BusinessException(AlertErrorCode.ALERTS_FULL);
    var subscribed =
        store.upsertSubscription(endpoint, p256dh.strip(), auth.strip(), hash(tokenText), now);
    return new Subscription(subscribed.subscriptionId(), tokenText);
  }

  public void unsubscribe(UUID subscriptionId, String token) {
    authorize(subscriptionId, token);
    store.deleteSubscription(subscriptionId);
  }

  /** 조건과 그 품목의 지금 가격·참 여부. */
  public record ConditionView(AlertStore.Condition condition, boolean matchedNow) {}

  public List<ConditionView> conditions(UUID subscriptionId, String token) {
    authorize(subscriptionId, token);
    store.touch(subscriptionId, clock.instant());
    return store.conditions(subscriptionId).stream().map(AlertService::view).toList();
  }

  public ConditionView addCondition(
      UUID subscriptionId,
      String token,
      long itemId,
      AlertComparator comparator,
      BigDecimal price) {
    authorize(subscriptionId, token);
    var now = clock.instant();
    var item =
        store
            .itemPrice(itemId)
            .filter(found -> !found.observedAt().isBefore(now.minus(properties.itemFreshness())))
            .orElseThrow(() -> new BusinessException(AlertErrorCode.ALERT_ITEM_NOT_ALERTABLE));
    if (store.conditionCount(subscriptionId) >= properties.maxConditionsPerSubscription())
      throw new BusinessException(AlertErrorCode.ALERT_TOO_MANY_CONDITIONS);
    // 이미 참이면 보내지 않고 시작한다(다시 거짓이 됐다가 참이 될 때 보냄).
    boolean matched = comparator.matches(item.currentMinPrice(), price);
    var condition = store.addCondition(subscriptionId, itemId, comparator, price, !matched, now);
    store.touch(subscriptionId, now);
    return new ConditionView(condition, matched);
  }

  public void deleteCondition(UUID subscriptionId, String token, UUID conditionId) {
    authorize(subscriptionId, token);
    if (!store.deleteCondition(subscriptionId, conditionId))
      throw new BusinessException(AlertErrorCode.ALERT_CONDITION_NOT_FOUND);
    store.touch(subscriptionId, clock.instant());
  }

  /** 알림을 걸 수 있는 품목(itemFreshness 안에 가격을 관측한 예열 품목). */
  public List<AlertStore.ItemPrice> items() {
    return store.itemPrices(clock.instant().minus(properties.itemFreshness()));
  }

  /** 예열이 받은 가격으로 조건을 판정한다. 발송은 {@link #dispatch()}가 따로 한다. */
  public void onPrices(MarketPricesObserved event) {
    var prices = new ArrayList<AlertStore.ItemPrice>();
    for (var price : event.prices())
      prices.add(
          new AlertStore.ItemPrice(
              price.id(), price.name(), price.currentMinPrice(), event.observedAt()));
    store.observe(prices, clock.instant().minus(properties.resendInterval()));
  }

  /** 발송 대기 조건을 분당 상한 안에서 보낸다. 넘는 것은 다음 주기로 미룬다. */
  public synchronized int dispatch() {
    var now = clock.instant();
    long window = now.toEpochMilli() / MINUTE.toMillis();
    if (window != sendWindow) {
      sendWindow = window;
      sentInWindow = 0;
    }
    int budget = properties.sendsPerMinute() - sentInWindow;
    if (budget <= 0) return 0;
    int sent = 0;
    for (var pending : store.claimPending(budget, now)) {
      sentInWindow++;
      var result =
          sender.send(pending.endpoint(), pending.p256dh(), pending.auth(), payload(pending));
      switch (result) {
        case SENT -> {
          store.markSent(pending.conditionId(), now);
          store.touch(pending.subscriptionId(), now);
          sent++;
        }
        case GONE -> {
          // 브라우저가 구독을 없앴다. 즉시 지운다(조건도 함께).
          store.deleteSubscription(pending.subscriptionId());
          log.info("만료된 알림 구독을 지웠습니다. subscriptionId={}", pending.subscriptionId());
        }
        case FAILED -> store.markSent(pending.conditionId(), now);
      }
    }
    return sent;
  }

  /** 오래 쓰지 않은 구독을 지운다. */
  public int cleanup() {
    int deleted = store.deleteInactive(clock.instant().minus(properties.inactiveRetention()));
    if (deleted > 0) log.info("오래 쓰지 않은 알림 구독을 지웠습니다. count={}", deleted);
    return deleted;
  }

  /** 알림 본문: 품목·가격·조건뿐(JSON). */
  static byte[] payload(AlertStore.Pending pending) {
    var json =
        "{\"itemId\":"
            + pending.itemId()
            + ",\"itemName\":\""
            + escape(pending.itemName())
            + "\",\"price\":"
            + pending.price().toPlainString()
            + ",\"comparator\":\""
            + pending.comparator().name()
            + "\",\"threshold\":"
            + pending.threshold().toPlainString()
            + "}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private void authorize(UUID subscriptionId, String token) {
    var expected = store.tokenHash(subscriptionId);
    if (token == null
        || token.isBlank()
        || expected.isEmpty()
        || !MessageDigest.isEqual(
            expected.get().getBytes(StandardCharsets.US_ASCII),
            hash(token.strip()).getBytes(StandardCharsets.US_ASCII)))
      throw new BusinessException(AlertErrorCode.ALERT_SUBSCRIPTION_NOT_FOUND);
  }

  private static ConditionView view(AlertStore.Condition condition) {
    return new ConditionView(
        condition, condition.comparator().matches(condition.currentMinPrice(), condition.price()));
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String escape(String value) {
    var out = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
      else out.append(c);
    }
    return out.toString();
  }
}
