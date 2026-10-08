package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.application.CharacterCollectionPort.Target;
import com.loakraken.character.application.CharacterCollectorService.Task;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CharacterCollectorServiceTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC);
  private final CharacterDetailsPort details = mock(CharacterDetailsPort.class);
  private final CharacterCollectionPort collection = mock(CharacterCollectionPort.class);
  private final CharacterHistoryService history = mock(CharacterHistoryService.class);
  private final CharacterCardService cards = mock(CharacterCardService.class);
  private final LostArkRequestGate gate = mock(LostArkRequestGate.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Target target = new Target("모험가", "루페온");

  @BeforeEach
  void 기본() {
    when(gate.backgroundAvailable(anyInt())).thenReturn(true);
  }

  @Test
  void 사용자_몫이_부족하거나_키가_없거나_꺼져_있으면_호출하지_않는다() {
    when(gate.backgroundAvailable(40)).thenReturn(false);
    assertThat(service("key", true).tick()).isEqualTo(Task.NONE);
    assertThat(service("", true).tick()).isEqualTo(Task.NONE);
    assertThat(service("key", false).tick()).isEqualTo(Task.NONE);
    verifyNoInteractions(details, collection);
    assertThat(
            registry.get("loakraken.stats.collector").tag("outcome", "yielded").counter().count())
        .isEqualTo(1);
  }

  @Test
  void 프로필_갱신은_관측을_기록하고_포지션을_저장하며_다음_회차는_원정대를_훑는다() {
    when(collection.nextProfileDue(
            eq(new BigDecimal("1640")), eq(Instant.parse("2026-10-01T03:00:00Z"))))
        .thenReturn(Optional.of(target));
    when(collection.nextRosterDue(any())).thenReturn(Optional.of(target));
    var profile =
        new CharacterProfile(
            "모험가",
            "루페온",
            "바드",
            70,
            new BigDecimal("1700"),
            null,
            null,
            null,
            null,
            null,
            List.of());
    when(details.fetchArmory("모험가"))
        .thenReturn(
            new com.loakraken.character.application.FetchedCharacter(
                CharacterTestFixture.details(
                    profile, new CharacterLoadout(List.of(), null, null, null, null, null), null),
                "{}",
                null));
    var siblings = List.of(new CharacterSibling("부캐", "루페온", "블레이드", 70, new BigDecimal("1680")));
    when(details.fetchSiblings("모험가")).thenReturn(siblings);
    when(collection.saveRoster(siblings, clock.instant())).thenReturn(1);

    var service = service("key", true);
    assertThat(service.tick()).isEqualTo(Task.PROFILE);
    verify(history).record(profile, clock.instant());
    verify(cards).record(any(), eq(clock.instant()));
    verify(collection).markProfileRefreshed(eq(target), any(), eq(clock.instant()));
    assertThat(service.tick()).isEqualTo(Task.ROSTER);
    verify(collection).markRosterScanned(target, clock.instant());
    assertThat(registry.get("loakraken.stats.collector.discovered").counter().count()).isEqualTo(1);
  }

  @Test
  void 없는_캐릭터는_다시_고르지_않게_표시하고_한도_초과는_다음_회차에_재시도한다() {
    when(collection.nextProfileDue(any(), any())).thenReturn(Optional.of(target));
    when(collection.nextRosterDue(any())).thenReturn(Optional.empty());
    when(details.fetchArmory("모험가"))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED));
    var service = service("key", true);
    assertThat(service.tick()).isEqualTo(Task.PROFILE);
    verify(collection).markProfileMissing(target, clock.instant());
    // 다음 회차는 원정대 차례지만 할 일이 없어 프로필로 넘어간다.
    assertThat(service.tick()).isEqualTo(Task.PROFILE);
    verify(collection, times(1)).markProfileMissing(any(), any());
    assertThat(
            registry.get("loakraken.stats.collector").tag("outcome", "not_found").counter().count())
        .isEqualTo(1);
  }

  @Test
  void 할_일이_없으면_호출하지_않는다() {
    when(collection.nextProfileDue(any(), any())).thenReturn(Optional.empty());
    when(collection.nextRosterDue(any())).thenReturn(Optional.empty());
    assertThat(service("key", true).tick()).isEqualTo(Task.NONE);
    verifyNoInteractions(details);
  }

  private CharacterCollectorService service(String key, boolean enabled) {
    var lostArk =
        new LostArkProperties(
            URI.create("http://127.0.0.1:1"),
            key,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            90,
            4,
            Duration.ofMinutes(5));
    var policy =
        new CharacterCollectionPolicy(
            enabled,
            Duration.ofSeconds(3),
            40,
            new BigDecimal("1640"),
            Duration.ofDays(7),
            Duration.ofDays(7));
    return new CharacterCollectorService(
        details, collection, history, cards, policy, gate, lostArk, registry, clock);
  }
}
