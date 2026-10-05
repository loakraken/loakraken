package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterMetrics;
import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.common.lostark.LostArkProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class ValkeyCharacterCacheTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final JsonMapper mapper = new JsonMapper();
  private final LostArkProperties properties =
      new LostArkProperties(
          URI.create("https://example.com"),
          "test-key",
          Duration.ofSeconds(3),
          Duration.ofSeconds(5),
          90,
          4,
          Duration.ofMinutes(5));
  private final ValkeyCharacterCache cache =
      new ValkeyCharacterCache(redis, mapper, properties, new CharacterMetrics(registry));

  ValkeyCharacterCacheTest() {
    when(redis.opsForValue()).thenReturn(values);
  }

  @Test
  void 적중과_미적중은_캐시_읽기마다_한번_기록한다() {
    when(values.get(anyString())).thenReturn(null, mapper.writeValueAsString(result()));
    assertThat(cache.find("비공개테스트명")).isEmpty();
    assertThat(cache.find("다른테스트명")).contains(result());
    assertThat(count("hit")).isEqualTo(1);
    assertThat(count("miss")).isEqualTo(1);
    assertThat(registry.getMeters())
        .allSatisfy(
            meter -> {
              assertThat(meter.getId().getTags()).hasSize(1);
              assertThat(meter.getId().getTags().getFirst().getKey()).isEqualTo("outcome");
              assertThat(meter.getId().toString()).doesNotContain("비공개테스트명", "다른테스트명", "test-key");
            });
  }

  @Test
  void 손상된_캐시는_삭제한_후_미적중으로_기록한다() {
    when(values.get(anyString())).thenReturn("broken-secret-json");
    assertThat(cache.find("모험가")).isEmpty();
    verify(redis).delete("character:v9:모험가:summary");
    assertThat(count("invalid")).isEqualTo(1);
    assertThat(count("miss")).isEqualTo(1);
  }

  @Test
  void 읽기_장애는_미적중으로_위장하지_않는다() {
    when(values.get(anyString()))
        .thenThrow(new DataAccessResourceFailureException("connection-secret"));
    assertThatThrownBy(() -> cache.find("모험가")).isInstanceOf(CharacterException.class);
    assertThat(count("read_error")).isEqualTo(1);
    assertThat(registry.find("loakraken.cache.operations").tag("outcome", "miss").counter())
        .isNull();
  }

  @Test
  void 손상_캐시_삭제_실패도_읽기_오류로_기록한다() {
    when(values.get(anyString())).thenReturn("broken");
    when(redis.delete(anyString()))
        .thenThrow(new DataAccessResourceFailureException("delete-secret"));
    assertThatThrownBy(() -> cache.find("모험가")).isInstanceOf(CharacterException.class);
    assertThat(count("invalid")).isEqualTo(1);
    assertThat(count("read_error")).isEqualTo(1);
    assertThat(registry.find("loakraken.cache.operations").tag("outcome", "miss").counter())
        .isNull();
  }

  @Test
  void 쓰기_성공과_실패를_별도로_기록한다() {
    cache.save("모험가", result());
    doThrow(new DataAccessResourceFailureException("write-secret"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    assertThatThrownBy(() -> cache.save("모험가", result())).isInstanceOf(CharacterException.class);
    assertThat(count("write_success")).isEqualTo(1);
    assertThat(count("write_error")).isEqualTo(1);
  }

  private double count(String outcome) {
    return registry.get("loakraken.cache.operations").tag("outcome", outcome).counter().count();
  }

  private CharacterResult result() {
    var profile =
        new CharacterProfile(
            "모험가", "루페온", "바드", 70, new BigDecimal("1700"), null, 300, null, null, null, List.of());
    return new CharacterResult(
        CharacterTestFixture.details(
            profile, new CharacterLoadout(List.of(), null, null, null, null, null), List.of()),
        Instant.EPOCH);
  }
}
