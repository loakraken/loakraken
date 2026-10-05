package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.MarketTestFixture;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class ValkeyMarketCacheTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final JsonMapper mapper = new JsonMapper();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Instant now = Instant.parse("2026-10-04T00:00:00Z");
  private final ValkeyMarketCache cache =
      new ValkeyMarketCache(
          redis,
          mapper,
          new MarketCacheProperties(
              Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(10)),
          new MarketMetrics(registry),
          Clock.fixed(now, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
  }

  @AfterEach
  void tags() {
    for (var meter : registry.getMeters()) {
      assertThat(meter.getId().toString()).doesNotContain("검증재료", "market:v1:", "http");
      assertThat(meter.getId().getTags())
          .allMatch(tag -> List.of("kind", "outcome").contains(tag.getKey()));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "broken",
        "{\"observedAt\":\"2026-10-04T00:00:00Z\",\"options\":{\"categories\":[],\"itemGrades\":[],\"itemTiers\":null,\"classes\":[]}}"
      })
  void 손상된_옵션은_삭제한_뒤_미적중으로_처리한다(String json) {
    when(values.get(anyString())).thenReturn(json);
    assertThat(cache.findOptions()).isEmpty();
    verify(redis).delete("market:v1:options");
    assertThat(count("options", "invalid")).isEqualTo(1);
    assertThat(count("options", "miss")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(longs = {-600, 1})
  void 만료되거나_미래인_수집시각은_적중으로_쓰지_않는다(long seconds) {
    when(values.get(anyString()))
        .thenReturn(
            mapper.writeValueAsString(
                new MarketOptionsResult(now.plusSeconds(seconds), MarketTestFixture.options())));
    assertThat(cache.findOptions()).isEmpty();
    verify(redis).delete("market:v1:options");
  }

  @Test
  void 가격_문맥이_다른_캐시는_사용하지_않는다() {
    var result =
        new MarketResult(
            MarketSource.RECENT_TRADE,
            now,
            MarketTestFixture.search(),
            new MarketPage(1, 10, 0, List.of()));
    when(values.get(anyString())).thenReturn(mapper.writeValueAsString(result));
    assertThat(cache.findPrices(MarketSource.ACTIVE_LISTING, MarketTestFixture.search())).isEmpty();
    verify(redis).delete(anyString());
    assertThat(count("prices", "invalid")).isEqualTo(1);
  }

  @Test
  void 읽기_장애와_손상_삭제_장애는_미적중이_아니다() {
    when(values.get(anyString()))
        .thenThrow(new DataAccessResourceFailureException("cache unavailable"));
    assertUnavailable(
        () -> cache.findPrices(MarketSource.ACTIVE_LISTING, MarketTestFixture.search()));
    doReturn("broken").when(values).get(anyString());
    when(redis.delete(anyString()))
        .thenThrow(new DataAccessResourceFailureException("cache unavailable"));
    assertUnavailable(cache::findOptions);
    assertThat(count("prices", "read_error")).isEqualTo(1);
    assertThat(count("options", "invalid")).isEqualTo(1);
    assertThat(count("options", "read_error")).isEqualTo(1);
    assertThat(registry.find("loakraken.market.cache.operations").tag("outcome", "miss").counter())
        .isNull();
  }

  @Test
  void TTL로_저장하고_쓰기_장애를_변환한다() {
    var result = new MarketOptionsResult(now, MarketTestFixture.options());
    cache.saveOptions(result);
    verify(values).set(eq("market:v1:options"), anyString(), eq(Duration.ofMinutes(10)));
    assertThat(count("options", "write_success")).isEqualTo(1);
    doThrow(new DataAccessResourceFailureException("cache unavailable"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    assertUnavailable(() -> cache.saveOptions(result));
    assertThat(count("options", "write_error")).isEqualTo(1);
  }

  @Test
  void 설정한_TTL은_양수여야_한다() {
    assertThatThrownBy(
            () ->
                new MarketCacheProperties(
                    Duration.ZERO, Duration.ofMinutes(10), Duration.ofMinutes(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MarketCacheProperties(
                    Duration.ofSeconds(30), Duration.ofSeconds(-1), Duration.ofMinutes(10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private double count(String kind, String outcome) {
    return registry
        .get("loakraken.market.cache.operations")
        .tags("kind", kind, "outcome", outcome)
        .counter()
        .count();
  }

  private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error ->
                assertThat(error.errorCode()).isEqualTo(MarketErrorCode.MARKET_CACHE_UNAVAILABLE));
  }
}
