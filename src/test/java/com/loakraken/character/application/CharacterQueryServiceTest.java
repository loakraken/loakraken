package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CharacterQueryServiceTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
  private final CharacterProfile profile =
      new CharacterProfile(
          "모험가", "루페온", "바드", 70, new BigDecimal("1700"), null, 300, null, null, null, List.of());
  private final CharacterDetails details =
      new CharacterDetails(
          profile, new CharacterLoadout(List.of(), null, null, null, null, null), List.of());

  @Test
  void 캐시_적중은_외부_호출과_수집시각_변경이_없다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = mock(CharacterCache.class);
    var cached = new CharacterResult(details, clock.instant().minusSeconds(60));
    when(cache.find("모험가")).thenReturn(Optional.of(cached));
    var service = new CharacterQueryService(port, cache, clock);
    assertThat(service.search(new SearchCharacterCommand(" 모험가 "))).isEqualTo(cached);
    verifyNoInteractions(port);
  }

  @Test
  void 동일_캐릭터_동시_요청은_한번만_갱신한다() throws Exception {
    var port = mock(CharacterDetailsPort.class);
    var data = new ConcurrentHashMap<String, CharacterResult>();
    var cache =
        new CharacterCache() {
          public Optional<CharacterResult> find(String name) {
            return Optional.ofNullable(data.get(name));
          }

          public void save(String name, CharacterResult result) {
            data.put(name, result);
          }
        };
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(port.fetch("모험가"))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 대기 초과");
              return details;
            });
    var service = new CharacterQueryService(port, cache, clock);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<CharacterResult>>();
      try {
        for (int i = 0; i < 16; i++)
          futures.add(executor.submit(() -> service.search(new SearchCharacterCommand("모험가"))));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      for (var future : futures)
        assertThat(future.get(5, TimeUnit.SECONDS).details()).isEqualTo(details);
    }
    verify(port, times(1)).fetch("모험가");
  }

  @Test
  void 갱신_실패_후_다음_요청은_다시_시도할_수_있다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = mock(CharacterCache.class);
    when(cache.find(anyString())).thenReturn(Optional.empty());
    when(port.fetch("모험가"))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR))
        .thenReturn(details);
    var service = new CharacterQueryService(port, cache, clock);
    assertThatThrownBy(() -> service.search(new SearchCharacterCommand("모험가")))
        .isInstanceOf(CharacterException.class);
    assertThat(service.search(new SearchCharacterCommand("모험가")).details()).isEqualTo(details);
    verify(port, times(2)).fetch("모험가");
  }

  @Test
  void 캐시_장애는_외부_API를_우회_호출하지_않는다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = mock(CharacterCache.class);
    when(cache.find(anyString()))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE));
    var service = new CharacterQueryService(port, cache, clock);
    assertThatThrownBy(() -> service.search(new SearchCharacterCommand("모험가")))
        .isInstanceOf(CharacterException.class);
    verifyNoInteractions(port);
  }
}
