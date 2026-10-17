package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 전투정보실 페이지 정보 서비스(#531): 캐시 쓰기 실패와 single-flight 안 캐시 재확인. 실제 공식 요청 0. */
class ArmoryPageServiceTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneOffset.UTC);
  private final CharacterCache cache = mock(CharacterCache.class);
  private final CharacterStopList stopList = mock(CharacterStopList.class);
  private final ArmoryPagePort pages = mock(ArmoryPagePort.class);
  private final FakeStore store = new FakeStore();
  private final ArmoryPageGuard guard =
      new ArmoryPageGuard(ArmoryPageGuardTest.policy(), clock, new SimpleMeterRegistry(), d -> {});
  private final ArmoryPageService service =
      new ArmoryPageService(
          ArmoryPageGuardTest.policy(),
          cache,
          stopList,
          store,
          pages,
          guard,
          new SimpleMeterRegistry(),
          clock);

  private void cached(String name) {
    var profile =
        new CharacterProfile(
            name,
            "서비스서버",
            "바드",
            70,
            new BigDecimal("1700"),
            null,
            null,
            null,
            null,
            null,
            List.of());
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null, List.of());
    when(cache.find(name))
        .thenReturn(
            Optional.of(
                new CharacterResult(
                    CharacterTestFixture.details(profile, loadout, List.of()), clock.instant())));
    when(stopList.recordable(name, "서비스서버")).thenReturn(true);
  }

  @Test
  void 캐시를_쓰지_못하면_실패_캐시_시간만큼_요청을_멈춘다() {
    cached("가");
    cached("나");
    store.writable = false;
    when(pages.fetch(anyString()))
        .thenReturn(new ArmoryPagePort.Page(List.of(new ArmoryPagePort.LifeSkill("낚시", 1))));
    assertThat(service.page("가").lifeSkills()).isNotNull();
    // 저장하지 못했으니 보호 캐시가 없다 → 다른 캐릭터도 쉬는 동안 공식 요청 없음.
    assertThat(service.page("나").lifeSkills()).isNull();
    assertThat(service.page("가").lifeSkills()).isNull();
    verify(pages, times(1)).fetch(anyString());
  }

  @Test
  void single_flight_안에서_캐시를_다시_보고_이미_있으면_부르지_않는다() {
    cached("다");
    // 첫 확인에는 없고(다른 요청이 받는 중), single-flight 안에서 다시 볼 때는 있다.
    store.findSequence.put(
        "다",
        new ArrayList<>(
            List.of(
                Optional.empty(),
                Optional.of(
                    new ArmoryPageStorePort.Entry(
                        clock.instant(), List.of(new ArmoryPagePort.LifeSkill("고고학", 70)))))));
    var view = service.page("다");
    assertThat(view.lifeSkills()).extracting(ArmoryPagePort.LifeSkill::level).containsExactly(70);
    verify(pages, never()).fetch(anyString());
  }

  private static final class FakeStore implements ArmoryPageStorePort {
    boolean writable = true;
    final Map<String, Entry> entries = new HashMap<>();
    final Map<String, List<Optional<Entry>>> findSequence = new HashMap<>();

    @Override
    public Optional<Entry> find(String characterName) {
      var sequence = findSequence.get(characterName);
      if (sequence != null && !sequence.isEmpty()) return sequence.remove(0);
      return Optional.ofNullable(entries.get(characterName));
    }

    @Override
    public boolean save(String characterName, Entry entry, Duration ttl) {
      if (!writable) return false;
      entries.put(characterName, entry);
      return true;
    }
  }
}
