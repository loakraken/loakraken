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
  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  private final CharacterMetrics metrics = new CharacterMetrics(registry);
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
  private final CharacterProfile profile =
      new CharacterProfile(
          "모험가", "루페온", "바드", 70, new BigDecimal("1700"), null, 300, null, null, null, List.of());
  private final CharacterDetails details =
      com.loakraken.CharacterTestFixture.details(
          profile, new CharacterLoadout(List.of(), null, null, null, null, null), List.of());
  private final FetchedCharacter fetched = new FetchedCharacter(details, "{}", "[]");
  private final CharacterSnapshotCommandService snapshots =
      mock(CharacterSnapshotCommandService.class);
  private final CharacterHistoryService history = mock(CharacterHistoryService.class);
  private final CharacterCardService cards = mock(CharacterCardService.class);

  @Test
  void 캐시_적중은_외부_호출과_수집시각_변경이_없다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = mock(CharacterCache.class);
    var cached = new CharacterResult(details, clock.instant().minusSeconds(60));
    when(cache.find("모험가")).thenReturn(Optional.of(cached));
    var service =
        new CharacterQueryService(
            port, cache, clock, snapshots, metrics, history, cards, openStopList());
    assertThat(service.search(new SearchCharacterCommand(" 모험가 "))).isEqualTo(cached);
    verifyNoInteractions(port, snapshots, history);
    assertThat(
            registry
                .get("loakraken.character.searches")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 동일_캐릭터_동시_요청은_한번만_갱신한다() throws Exception {
    var port = mock(CharacterDetailsPort.class);
    var cache = new MemoryCache();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(port.fetch("모험가"))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 대기 초과");
              return fetched;
            });
    var service =
        new CharacterQueryService(
            port, cache, clock, snapshots, metrics, history, cards, openStopList());
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
    assertThat(cache.find("모험가").orElseThrow().refreshAvailableAt())
        .isEqualTo(clock.instant().plusSeconds(300));
    verify(port, times(1)).fetch("모험가");
    assertThat(
            registry
                .get("loakraken.character.searches")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(16);
    verify(snapshots, times(1)).save(fetched, clock.instant());
    verify(history, times(1)).record(profile, clock.instant());
  }

  @Test
  void 갱신_실패_후_다음_요청은_다시_시도할_수_있다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = mock(CharacterCache.class);
    when(cache.find(anyString())).thenReturn(Optional.empty());
    when(cache.ttl()).thenReturn(java.time.Duration.ofMinutes(5));
    when(port.fetch("모험가"))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR))
        .thenReturn(fetched);
    var service =
        new CharacterQueryService(
            port, cache, clock, snapshots, metrics, history, cards, openStopList());
    assertThatThrownBy(() -> service.search(new SearchCharacterCommand("모험가")))
        .isInstanceOf(CharacterException.class);
    assertThat(service.search(new SearchCharacterCommand("모험가")).details()).isEqualTo(details);
    verify(port, times(2)).fetch("모험가");
    assertThat(
            registry
                .get("loakraken.character.searches")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("loakraken.character.searches")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void DB_저장_실패는_503으로_변환하고_캐시에_저장하지_않는다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = mock(CharacterCache.class);
    when(cache.find(anyString())).thenReturn(Optional.empty());
    when(port.fetch("모험가")).thenReturn(fetched);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("internal-secret"))
        .when(snapshots)
        .save(fetched, clock.instant());
    var service =
        new CharacterQueryService(
            port, cache, clock, snapshots, metrics, history, cards, openStopList());
    assertThatThrownBy(() -> service.search(new SearchCharacterCommand("모험가")))
        .isInstanceOfSatisfying(
            CharacterException.class,
            exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE));
    verify(cache, never()).save(anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void 캐시_장애는_외부_API를_우회_호출하지_않는다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = mock(CharacterCache.class);
    when(cache.find(anyString()))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE));
    var service =
        new CharacterQueryService(
            port, cache, clock, snapshots, metrics, history, cards, openStopList());
    assertThatThrownBy(() -> service.search(new SearchCharacterCommand("모험가")))
        .isInstanceOf(CharacterException.class);
    verifyNoInteractions(port);
    assertThat(
            registry
                .get("loakraken.character.searches")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 전투정보만_조회하면_원정대를_호출하지_않고_스냅샷을_저장하지_않는다() {
    var port = mock(CharacterDetailsPort.class);
    var cache = new MemoryCache();
    var armoryOnly =
        new FetchedCharacter(
            com.loakraken.CharacterTestFixture.details(
                profile, new CharacterLoadout(List.of(), null, null, null, null, null), null),
            "{}",
            null);
    when(port.fetchArmory("모험가")).thenReturn(armoryOnly);
    var service =
        new CharacterQueryService(
            port, cache, clock, snapshots, metrics, history, cards, openStopList());
    var first = service.search(new SearchCharacterCommand("모험가", false));
    var second = service.search(new SearchCharacterCommand("모험가", false));
    assertThat(first.details().siblings()).isNull();
    assertThat(first.refreshAvailableAt()).isEqualTo(clock.instant().plusSeconds(300));
    assertThat(second).isEqualTo(first);
    verify(port, times(1)).fetchArmory("모험가");
    verify(history, times(1)).record(profile, clock.instant());
    verify(port, never()).fetch(anyString());
    verify(port, never()).fetchSiblings(anyString());
    verifyNoInteractions(snapshots);
    assertThat(cache.summaries).isEmpty();
    assertThat(cache.armories).containsKey("모험가");
  }

  @Test
  void 원정대_조회는_별도_캐시를_쓰고_동시_요청을_한번으로_합친다() throws Exception {
    var port = mock(CharacterDetailsPort.class);
    var cache = new MemoryCache();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var siblings =
        List.of(
            new com.loakraken.character.domain.CharacterSibling(
                "모험가", "루페온", "바드", 70, new BigDecimal("1700")));
    when(port.fetchSiblings("모험가"))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 대기 초과");
              return siblings;
            });
    var service =
        new CharacterQueryService(
            port, cache, clock, snapshots, metrics, history, cards, openStopList());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<CharacterSiblingsResult>>();
      try {
        for (int i = 0; i < 16; i++)
          futures.add(executor.submit(() -> service.siblings(new SearchCharacterCommand(" 모험가 "))));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      for (var future : futures)
        assertThat(future.get(5, TimeUnit.SECONDS).siblings()).isEqualTo(siblings);
    }
    var cachedSiblings = service.siblings(new SearchCharacterCommand("모험가"));
    assertThat(cachedSiblings.fetchedAt()).isEqualTo(clock.instant());
    assertThat(cachedSiblings.refreshAvailableAt()).isEqualTo(clock.instant().plusSeconds(300));
    verify(port, times(1)).fetchSiblings("모험가");
    verify(port, never()).fetch(anyString());
    verifyNoInteractions(snapshots);
    assertThat(cache.siblings).containsKey("모험가");
    assertThat(registry.find("loakraken.character.searches").counter()).isNull();
  }

  private static final class MemoryCache implements CharacterCache {
    final ConcurrentHashMap<String, CharacterResult> summaries = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, CharacterResult> armories = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, CharacterSiblingsResult> siblings = new ConcurrentHashMap<>();

    public java.time.Duration ttl() {
      return java.time.Duration.ofMinutes(5);
    }

    public Optional<CharacterResult> find(String name) {
      return Optional.ofNullable(summaries.get(name));
    }

    public void save(String name, CharacterResult result) {
      summaries.put(name, result);
    }

    public Optional<CharacterResult> findArmory(String name) {
      return Optional.ofNullable(armories.get(name));
    }

    public void saveArmory(String name, CharacterResult result) {
      armories.put(name, result);
    }

    public Optional<CharacterSiblingsResult> findSiblings(String name) {
      return Optional.ofNullable(siblings.get(name));
    }

    public void saveSiblings(String name, CharacterSiblingsResult result) {
      siblings.put(name, result);
    }
  }

  /** 처리정지 목록이 비어 있음(#351). */
  static CharacterStopList openStopList() {
    return new CharacterStopList(org.mockito.Mockito.mock(CharacterPublicSummaryPort.class));
  }
}
