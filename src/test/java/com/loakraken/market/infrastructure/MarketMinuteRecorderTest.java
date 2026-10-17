package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.loakraken.market.application.MarketMinuteService;
import com.loakraken.market.application.MarketMinuteStore;
import com.loakraken.market.application.MarketPricesObserved;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 분 단위 시세 저장이 실패해도 예외를 밖으로 내지 않아 예열을 막지 않는다(K35). */
class MarketMinuteRecorderTest {
  @Test
  void 저장이_실패해도_예외를_내지_않는다() {
    var failing =
        new MarketMinuteStore() {
          @Override
          public void save(List<Point> points) {
            throw new IllegalStateException("저장 실패");
          }

          @Override
          public List<Point> find(long itemId, Instant from) {
            return List.of();
          }

          @Override
          public int deleteBefore(Instant before) {
            throw new IllegalStateException("정리 실패");
          }
        };
    var recorder = new MarketMinuteRecorder(new MarketMinuteService(failing, Clock.systemUTC()));
    var event =
        new MarketPricesObserved(
            Instant.now(),
            Duration.ofSeconds(30),
            List.of(new MarketPrice(1, "품목", "일반", null, 1, null, null, null, BigDecimal.TEN)));
    assertThatCode(() -> recorder.onPrices(event)).doesNotThrowAnyException();
    assertThatCode(recorder::cleanup).doesNotThrowAnyException();
  }
}
