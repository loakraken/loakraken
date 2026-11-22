package com.loakraken.market.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketAuctionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** 장신구 칸 예열(PM 총괄 11-16): 1분에 한 칸, 60분보다 오래된 칸만, 꺼져 있거나 키·임대가 없으면 부르지 않는다. */
class AccessoryCellWarmupTest {
  private final MarketAuctionService auctions = mock(MarketAuctionService.class);
  private final LeaderLease lease = mock(LeaderLease.class);
  private final MarketWarmupProperties warmupProperties = mock(MarketWarmupProperties.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private AccessoryCellWarmup warmup(boolean enabled, String apiKey) {
    when(warmupProperties.interval()).thenReturn(Duration.ofMinutes(9));
    when(auctions.warmAccessories(anyInt(), any(), any(), any()))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed"));
    return new AccessoryCellWarmup(
        auctions,
        mock(MarketHistoryWarmup.class),
        new AccessoryWarmupProperties(enabled, Duration.ofMinutes(60)),
        warmupProperties,
        new LostArkProperties(
            java.net.URI.create("https://example.com"),
            apiKey,
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            90,
            4,
            Duration.ofMinutes(5)),
        mock(LostArkRequestGate.class),
        lease,
        registry,
        Clock.fixed(Instant.parse("2026-11-16T09:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void 일분에_한_칸을_60분보다_오래된_칸만_다른_예열과_같은_임대로_받고_지표를_남긴다() {
    when(lease.hold(eq(MarketHistoryWarmup.LEASE), any())).thenReturn(true);
    warmup(true, "key").warmAccessoryCells();
    verify(auctions).warmAccessories(eq(1), eq(Duration.ofMinutes(60)), any(), any());
    verify(lease).hold(MarketHistoryWarmup.LEASE, Duration.ofMinutes(10));
    Assertions.assertThat(
            registry
                .get("loakraken.market.warmup")
                .tag("target", "accessories")
                .tag("outcome", "refreshed")
                .counter()
                .count())
        .isEqualTo(1.0);
    Assertions.assertThat(registry.get("loakraken.market.warmup_accessories").gauge().value())
        .isEqualTo(Instant.parse("2026-11-16T09:00:00Z").getEpochSecond());
  }

  /** 이어진 두 틱이 같은 칸을 다시 받으면(마지막 값 저장 실패) 경고 지표를 센다(보안 11-16 #985 메모). 다른 칸이면 세지 않는다. */
  @Test
  void 같은_칸을_이어서_다시_받으면_반복_지표를_센다() {
    when(lease.hold(eq(MarketHistoryWarmup.LEASE), any())).thenReturn(true);
    var job = warmup(true, "key");
    when(auctions.warmAccessories(anyInt(), any(), any(), any()))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:a"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:b"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:b"));
    job.warmAccessoryCells();
    job.warmAccessoryCells();
    Assertions.assertThat(registry.find("loakraken.market.warmup_repeat").counter()).isNull();
    job.warmAccessoryCells();
    Assertions.assertThat(
            registry
                .get("loakraken.market.warmup_repeat")
                .tag("target", "accessories")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /** 반복 지표는 매번 세고, 경고 로그는 이어짐의 첫 반복에만(#997 리뷰 뒤속). 다른 칸이 끼면 다음 이어짐에서 다시 한 번. */
  @Test
  void 같은_칸_반복의_경고는_이어짐마다_한_번만_남긴다() {
    when(lease.hold(eq(MarketHistoryWarmup.LEASE), any())).thenReturn(true);
    var job = warmup(true, "key");
    when(auctions.warmAccessories(anyInt(), any(), any(), any()))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:a"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:b"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:b"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:b"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:b"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:c"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:c"));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(AccessoryCellWarmup.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      for (int k = 0; k < 7; k++) job.warmAccessoryCells();
    } finally {
      logger.detachAppender(appender);
    }
    Assertions.assertThat(
            registry
                .get("loakraken.market.warmup_repeat")
                .tag("target", "accessories")
                .counter()
                .count())
        .isEqualTo(4.0);
    Assertions.assertThat(appender.list)
        .filteredOn(e -> e.getFormattedMessage().startsWith("event=market_accessory_warmup_repeat"))
        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "event=market_accessory_warmup_repeat key=ACC:b",
            "event=market_accessory_warmup_repeat key=ACC:c");
  }

  /**
   * 코드 리뷰 #997 재실행 P3: 받을 칸이 없는 틱(모든 칸이 주기 안)이 끼면 '이어진 두 틱'이 아니다. 칸 A를 받고 쉬다가 A가 다시 오래되어 받는 것은 정상이라
   * 반복으로 세지 않는다(저장 실패 거짓 경고 없음).
   */
  @Test
  void 쉬는_틱이_끼면_같은_칸을_다시_받아도_반복으로_세지_않는다() {
    when(lease.hold(eq(MarketHistoryWarmup.LEASE), any())).thenReturn(true);
    var job = warmup(true, "key");
    when(auctions.warmAccessories(anyInt(), any(), any(), any()))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:a"))
        .thenReturn(new MarketAuctionService.GemWarm(0, "idle"))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed", "ACC:a"));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(AccessoryCellWarmup.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      for (int k = 0; k < 3; k++) job.warmAccessoryCells();
    } finally {
      logger.detachAppender(appender);
    }
    Assertions.assertThat(registry.find("loakraken.market.warmup_repeat").counter()).isNull();
    Assertions.assertThat(appender.list)
        .noneMatch(e -> e.getFormattedMessage().startsWith("event=market_accessory_warmup_repeat"));
  }

  @Test
  void 꺼져_있거나_키가_없거나_임대가_없으면_부르지_않는다() {
    when(lease.hold(any(), any())).thenReturn(true);
    warmup(false, "key").warmAccessoryCells();
    warmup(true, "").warmAccessoryCells();
    when(lease.hold(any(), any())).thenReturn(false);
    warmup(true, "key").warmAccessoryCells();
    verify(auctions, never()).warmAccessories(anyInt(), any(), any(), any());
  }
}
