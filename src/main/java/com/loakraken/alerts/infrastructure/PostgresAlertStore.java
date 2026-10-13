package com.loakraken.alerts.infrastructure;

import com.loakraken.alerts.application.AlertStore;
import com.loakraken.alerts.domain.AlertComparator;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** 시세 알림 저장소(M33). 구독 주소·키 원문을 로그에 남기지 않는다(SQL 바인딩만). */
class PostgresAlertStore implements AlertStore {
  private final JdbcClient jdbc;

  PostgresAlertStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public Subscribed upsertSubscription(
      String endpoint, String p256dh, String auth, String tokenHash, Instant now) {
    var at = at(now);
    var existing =
        jdbc.sql(
                "SELECT subscription_id FROM push_subscription WHERE endpoint = :endpoint FOR UPDATE")
            .param("endpoint", endpoint)
            .query(UUID.class)
            .optional();
    if (existing.isPresent()) {
      // 같은 브라우저 구독을 다시 등록: 키·토큰을 바꾸고(옛 토큰 무효) 옛 조건을 지운다.
      jdbc.sql(
              """
          UPDATE push_subscription SET p256dh = :p256dh, auth = :auth, manage_token_hash = :hash,
            last_used_at = :at WHERE subscription_id = :id
          """)
          .param("p256dh", p256dh)
          .param("auth", auth)
          .param("hash", tokenHash)
          .param("at", at)
          .param("id", existing.get())
          .update();
      jdbc.sql("DELETE FROM price_alert WHERE subscription_id = :id")
          .param("id", existing.get())
          .update();
      return new Subscribed(existing.get(), false);
    }
    var id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO push_subscription (subscription_id, endpoint, p256dh, auth, manage_token_hash,
          created_at, last_used_at)
        VALUES (:id, :endpoint, :p256dh, :auth, :hash, :at, :at)
        ON CONFLICT (endpoint) DO NOTHING
        """)
        .param("id", id)
        .param("endpoint", endpoint)
        .param("p256dh", p256dh)
        .param("auth", auth)
        .param("hash", tokenHash)
        .param("at", at)
        .update();
    // 동시에 같은 endpoint가 먼저 들어갔으면 그 행을 새 키·토큰으로 덮는다.
    var stored =
        jdbc.sql("SELECT subscription_id FROM push_subscription WHERE endpoint = :endpoint")
            .param("endpoint", endpoint)
            .query(UUID.class)
            .single();
    if (!stored.equals(id)) return upsertSubscription(endpoint, p256dh, auth, tokenHash, now);
    return new Subscribed(id, true);
  }

  @Override
  public long subscriptionCount() {
    return jdbc.sql("SELECT COUNT(*) FROM push_subscription").query(Long.class).single();
  }

  @Override
  public boolean endpointExists(String endpoint) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM push_subscription WHERE endpoint = :endpoint)")
        .param("endpoint", endpoint)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<String> tokenHash(UUID subscriptionId) {
    return jdbc.sql("SELECT manage_token_hash FROM push_subscription WHERE subscription_id = :id")
        .param("id", subscriptionId)
        .query(String.class)
        .optional();
  }

  @Override
  public void touch(UUID subscriptionId, Instant now) {
    jdbc.sql(
            "UPDATE push_subscription SET last_used_at = GREATEST(last_used_at, :at) WHERE subscription_id = :id")
        .param("at", at(now))
        .param("id", subscriptionId)
        .update();
  }

  @Override
  public boolean deleteSubscription(UUID subscriptionId) {
    return jdbc.sql("DELETE FROM push_subscription WHERE subscription_id = :id")
            .param("id", subscriptionId)
            .update()
        > 0;
  }

  @Override
  public boolean deleteSubscription(String endpoint) {
    return jdbc.sql("DELETE FROM push_subscription WHERE endpoint = :endpoint")
            .param("endpoint", endpoint)
            .update()
        > 0;
  }

  @Override
  public int deleteInactive(Instant before) {
    return jdbc.sql("DELETE FROM push_subscription WHERE last_used_at < :before")
        .param("before", at(before))
        .update();
  }

  @Override
  public int deleteEmpty(Instant before) {
    return jdbc.sql(
            """
        DELETE FROM push_subscription s WHERE s.last_used_at < :before
          AND NOT EXISTS (SELECT 1 FROM price_alert a WHERE a.subscription_id = s.subscription_id)
        """)
        .param("before", at(before))
        .update();
  }

  @Override
  public List<Condition> conditions(UUID subscriptionId) {
    return jdbc.sql(
            """
        SELECT a.alert_id, a.item_id, i.item_name, a.comparator, a.price, i.current_min_price,
          i.observed_at, a.last_sent_at, a.created_at
        FROM price_alert a JOIN alert_item_price i ON i.item_id = a.item_id
        WHERE a.subscription_id = :id ORDER BY a.created_at, a.alert_id
        """)
        .param("id", subscriptionId)
        .query((row, index) -> condition(row))
        .list();
  }

  @Override
  public int conditionCount(UUID subscriptionId) {
    return jdbc.sql("SELECT COUNT(*) FROM price_alert WHERE subscription_id = :id")
        .param("id", subscriptionId)
        .query(Integer.class)
        .single();
  }

  @Override
  public Condition addCondition(
      UUID subscriptionId,
      long itemId,
      AlertComparator comparator,
      BigDecimal price,
      boolean armed,
      Instant now) {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO price_alert (alert_id, subscription_id, item_id, comparator, price, armed, created_at)
        VALUES (:id, :subscription, :item, :comparator, :price, :armed, :at)
        """)
        .param("id", id)
        .param("subscription", subscriptionId)
        .param("item", itemId)
        .param("comparator", comparator.name())
        .param("price", price)
        .param("armed", armed)
        .param("at", at(now))
        .update();
    return conditions(subscriptionId).stream()
        .filter(condition -> condition.conditionId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public boolean deleteCondition(UUID subscriptionId, UUID conditionId) {
    return jdbc.sql(
                "DELETE FROM price_alert WHERE alert_id = :id AND subscription_id = :subscription")
            .param("id", conditionId)
            .param("subscription", subscriptionId)
            .update()
        > 0;
  }

  @Override
  public Optional<ItemPrice> itemPrice(long itemId) {
    return jdbc.sql(
            "SELECT item_id, item_name, current_min_price, observed_at FROM alert_item_price WHERE item_id = :id")
        .param("id", itemId)
        .query((row, index) -> itemPrice(row))
        .optional();
  }

  @Override
  public List<ItemPrice> itemPrices(Instant since) {
    return jdbc.sql(
            """
        SELECT item_id, item_name, current_min_price, observed_at FROM alert_item_price
        WHERE observed_at >= :since ORDER BY item_name, item_id
        """)
        .param("since", at(since))
        .query((row, index) -> itemPrice(row))
        .list();
  }

  @Override
  @Transactional
  public void observe(List<ItemPrice> prices, Instant resendBefore) {
    for (var price : prices) {
      // 예열 결과가 순서 없이 와도 더 최신 관측만 반영하고, 오래된 관측으로는 조건을 판정하지 않는다.
      int updated =
          jdbc.sql(
                  """
          INSERT INTO alert_item_price (item_id, item_name, current_min_price, observed_at)
          VALUES (:id, :name, :price, :at)
          ON CONFLICT (item_id) DO UPDATE SET item_name = EXCLUDED.item_name,
            current_min_price = EXCLUDED.current_min_price, observed_at = EXCLUDED.observed_at
          WHERE alert_item_price.observed_at <= EXCLUDED.observed_at
          """)
              .param("id", price.itemId())
              .param("name", price.itemName())
              .param("price", price.currentMinPrice())
              .param("at", at(price.observedAt()))
              .update();
      if (updated == 0) continue;
      if (price.currentMinPrice() == null) {
        // 매물이 없으면 어느 조건도 참이 아니다: 다시 걸 수 있게 armed를 켠다.
        jdbc.sql("UPDATE price_alert SET armed = TRUE WHERE item_id = :id AND NOT armed")
            .param("id", price.itemId())
            .update();
        continue;
      }
      // 거짓 → 참 전환: 재발송 간격이 지났으면 발송 대기, 아니면 발송 없이 전환만 쓴다.
      jdbc.sql(
              """
          UPDATE price_alert SET armed = FALSE,
            pending_price = CASE WHEN last_sent_at IS NULL OR last_sent_at <= :resendBefore
              THEN :price ELSE pending_price END
          WHERE item_id = :id AND armed
            AND ((comparator = 'AT_OR_BELOW' AND :price <= price)
              OR (comparator = 'AT_OR_ABOVE' AND :price >= price))
          """)
          .param("price", price.currentMinPrice())
          .param("resendBefore", at(resendBefore))
          .param("id", price.itemId())
          .update();
      // 참 → 거짓: 다시 걸 수 있게 한다.
      jdbc.sql(
              """
          UPDATE price_alert SET armed = TRUE
          WHERE item_id = :id AND NOT armed
            AND NOT ((comparator = 'AT_OR_BELOW' AND :price <= price)
              OR (comparator = 'AT_OR_ABOVE' AND :price >= price))
          """)
          .param("price", price.currentMinPrice())
          .param("id", price.itemId())
          .update();
    }
  }

  @Override
  @Transactional
  public List<Pending> claimPending(int limit, Instant now) {
    var rows =
        jdbc.sql(
                """
            SELECT a.alert_id, a.subscription_id, s.endpoint, s.p256dh, s.auth, a.item_id, i.item_name,
              a.comparator, a.price, a.pending_price
            FROM price_alert a
            JOIN push_subscription s ON s.subscription_id = a.subscription_id
            JOIN alert_item_price i ON i.item_id = a.item_id
            WHERE a.pending_price IS NOT NULL
            ORDER BY a.created_at, a.alert_id
            LIMIT :limit
            FOR UPDATE OF a SKIP LOCKED
            """)
            .param("limit", limit)
            .query(
                (row, index) ->
                    new Pending(
                        row.getObject("alert_id", UUID.class),
                        row.getObject("subscription_id", UUID.class),
                        row.getString("endpoint"),
                        row.getString("p256dh"),
                        row.getString("auth"),
                        row.getLong("item_id"),
                        row.getString("item_name"),
                        AlertComparator.valueOf(row.getString("comparator")),
                        row.getBigDecimal("price"),
                        row.getBigDecimal("pending_price")))
            .list();
    // 가져간 조건은 대기에서 뺀다(발송 결과와 상관없이 같은 전환으로 두 번 보내지 않음).
    for (var row : rows)
      jdbc.sql("UPDATE price_alert SET pending_price = NULL WHERE alert_id = :id")
          .param("id", row.conditionId())
          .update();
    return rows;
  }

  @Override
  public void requeue(UUID conditionId, BigDecimal price) {
    jdbc.sql(
            "UPDATE price_alert SET pending_price = :price WHERE alert_id = :id AND pending_price IS NULL")
        .param("price", price)
        .param("id", conditionId)
        .update();
  }

  @Override
  public void markSent(UUID conditionId, Instant at) {
    jdbc.sql("UPDATE price_alert SET last_sent_at = :at WHERE alert_id = :id")
        .param("at", at(at))
        .param("id", conditionId)
        .update();
  }

  private static Condition condition(ResultSet row) throws SQLException {
    return new Condition(
        row.getObject("alert_id", UUID.class),
        row.getLong("item_id"),
        row.getString("item_name"),
        AlertComparator.valueOf(row.getString("comparator")),
        row.getBigDecimal("price"),
        row.getBigDecimal("current_min_price"),
        instant(row.getObject("observed_at", OffsetDateTime.class)),
        instant(row.getObject("last_sent_at", OffsetDateTime.class)),
        instant(row.getObject("created_at", OffsetDateTime.class)));
  }

  private static ItemPrice itemPrice(ResultSet row) throws SQLException {
    return new ItemPrice(
        row.getLong("item_id"),
        row.getString("item_name"),
        row.getBigDecimal("current_min_price"),
        instant(row.getObject("observed_at", OffsetDateTime.class)));
  }

  private static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
