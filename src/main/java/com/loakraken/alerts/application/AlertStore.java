package com.loakraken.alerts.application;

import com.loakraken.alerts.domain.AlertComparator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 시세 알림 저장소(M33). 구독 주소·키 원문은 이 포트 밖으로 로그에 나가지 않게 다룬다. */
public interface AlertStore {
  /** 관리 토큰 해시 대신 원문을 넘기지 않는다. 같은 endpoint가 있으면 키·토큰을 바꾸고 옛 조건을 지운다. */
  Subscribed upsertSubscription(
      String endpoint, String p256dh, String auth, String tokenHash, Instant now);

  long subscriptionCount();

  boolean endpointExists(String endpoint);

  /** 구독과 토큰 해시. 없으면 빈 값. */
  Optional<String> tokenHash(UUID subscriptionId);

  void touch(UUID subscriptionId, Instant now);

  boolean deleteSubscription(UUID subscriptionId);

  /** 그 주소의 구독을 지운다(확인 푸시에서 푸시 서비스가 구독 없음이라고 할 때). */
  boolean deleteSubscription(String endpoint);

  int deleteInactive(Instant before);

  /** 조건이 하나도 없고 before 전부터 쓰지 않은 구독을 지운다. */
  int deleteEmpty(Instant before);

  List<Condition> conditions(UUID subscriptionId);

  int conditionCount(UUID subscriptionId);

  Condition addCondition(
      UUID subscriptionId,
      long itemId,
      AlertComparator comparator,
      BigDecimal price,
      boolean armed,
      Instant now);

  boolean deleteCondition(UUID subscriptionId, UUID conditionId);

  Optional<ItemPrice> itemPrice(long itemId);

  List<ItemPrice> itemPrices(Instant since);

  /**
   * 관측 가격을 저장하고 조건을 판정한다. 참이면서 armed이고 재발송 간격이 지난 조건은 발송 대기(pending)로 두고 armed를 끈다. 재발송 간격 안이면 발송
   * 없이 armed만 끈다. 거짓인 조건은 armed를 켠다.
   */
  void observe(List<ItemPrice> prices, Instant resendBefore);

  /** 발송 대기 조건을 최대 limit개 가져온다(여러 인스턴스가 같은 조건을 동시에 가져가지 않게 잠근다). */
  List<Pending> claimPending(int limit, Instant now);

  void markSent(UUID conditionId, Instant at);

  /** 일시 실패한 조건을 다시 발송 대기로 둔다. 그사이 새 대기 가격이 들어왔으면 그대로 둔다. */
  void requeue(UUID conditionId, java.math.BigDecimal price);

  record Subscribed(UUID subscriptionId, boolean created) {}

  record Condition(
      UUID conditionId,
      long itemId,
      String itemName,
      AlertComparator comparator,
      BigDecimal price,
      BigDecimal currentMinPrice,
      Instant observedAt,
      Instant lastSentAt,
      Instant createdAt) {}

  record ItemPrice(long itemId, String itemName, BigDecimal currentMinPrice, Instant observedAt) {}

  /** 발송할 조건과 그 구독의 주소·키. 로그에 남기지 않는다. */
  record Pending(
      UUID conditionId,
      UUID subscriptionId,
      String endpoint,
      String p256dh,
      String auth,
      long itemId,
      String itemName,
      AlertComparator comparator,
      BigDecimal threshold,
      BigDecimal price) {
    @Override
    public String toString() {
      return "Pending[conditionId=" + conditionId + ", subscriptionId=" + subscriptionId + "]";
    }
  }
}
