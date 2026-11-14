package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** 줄 건너뛰기: 지표는 매번 세고, WARN 로그는 (품목, 이유)마다 하루(KST 날짜)에 한 번만 남긴다. 예열마다 같은 품목을 다시 받아도 로그가 쌓이지 않게. */
class PostgresMarketDailyStoreSkipLogTest {
  private static final LocalDate TODAY = LocalDate.parse("2026-11-13");
  private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final PostgresMarketDailyStore store =
      new PostgresMarketDailyStore(jdbc, new MarketMetrics(registry));
  private final Logger logger = (Logger) LoggerFactory.getLogger(PostgresMarketDailyStore.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attach() {
    when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[] {1});
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void 같은_품목_같은_이유는_하루에_한_번만_로그를_남기고_지표는_매번_센다() {
    store.upsert(collision(1), TODAY, NOW);
    store.upsert(collision(1), TODAY, NOW); // 예열이 같은 날 다시 받음
    store.upsert(collision(2), TODAY, NOW); // 다른 품목은 따로 남김
    store.upsert(collision(1), TODAY.plusDays(1), NOW); // 다음 날은 다시 남김

    assertThat(
            registry
                .counter("loakraken.market.daily_record", "event", "skipped_variant_key")
                .count())
        .isEqualTo(4);
    assertThat(skipLogs()).containsExactly("1/variant_key", "2/variant_key", "1/variant_key");
  }

  private List<String> skipLogs() {
    return appender.list.stream()
        .filter(event -> event.getFormattedMessage().startsWith("event=market_daily_row_skipped"))
        .map(
            event -> {
              var args = event.getArgumentArray();
              return args[0] + "/" + args[1];
            })
        .toList();
  }

  /** 거래 가능 횟수·묶음이 같은 변형 둘(같은 칸) → 두 번째 변형을 건너뛴다. */
  private static MarketItemHistory collision(long itemId) {
    var day = new MarketItemHistory.DailyStat(LocalDate.parse("2026-11-12"), BigDecimal.TEN, 5);
    return new MarketItemHistory(
        itemId,
        List.of(
            new MarketItemHistory.Variant("a", null, null, List.of(day)),
            new MarketItemHistory.Variant("b", null, null, List.of(day))));
  }
}
