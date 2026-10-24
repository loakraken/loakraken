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
import com.loakraken.common.lostark.LeaderLease;
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
  private final LeaderLease lease = mock(LeaderLease.class);
  private final CharacterPublicSummaryPort stored = mock(CharacterPublicSummaryPort.class);
  private CharacterSeedList seeds =
      new CharacterSeedList(new CharacterSeedPolicy(false, List.of(), ""));
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Target target = new Target("모험가", "루페온");

  @BeforeEach
  void 기본() {
    when(gate.backgroundAvailable(anyInt())).thenReturn(true);
    when(lease.hold(any(), any())).thenReturn(true);
    when(gate.withReserve(anyInt(), any()))
        .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());
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
    verify(cards).record(any(), any(), eq(clock.instant()));
    verify(collection).markProfileRefreshed(eq(target), any(), eq(clock.instant()));
    assertThat(service.tick()).isEqualTo(Task.ROSTER);
    verify(collection).markRosterScanned(target, clock.instant());
    assertThat(registry.get("loakraken.stats.collector.discovered").counter().count()).isEqualTo(1);
    // 마지막 성공 시각 게이지(#143)
    assertThat(
            registry
                .get("loakraken.background.last_success")
                .tag("job", "collector")
                .gauge()
                .value())
        .isEqualTo(clock.instant().getEpochSecond());
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
  void 리더_임대를_얻지_못하면_돌지_않는다() {
    when(lease.hold(any(), any())).thenReturn(false);
    assertThat(service("key", true).tick()).isEqualTo(Task.NONE);
    verifyNoInteractions(details, collection);
    assertThat(
            registry
                .get("loakraken.stats.collector")
                .tag("outcome", "not_leader")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 시드가_남아_있으면_먼저_원정대를_훑고_제외_이름과_이미_훑은_이름은_공식_호출_없이_건너뛴다() {
    seeds =
        new CharacterSeedList(
            new CharacterSeedPolicy(true, List.of("제외이름", "이미훑음", "시드하나", "시드하나"), ""));
    when(stored.excludedAnyServer("제외이름")).thenReturn(true);
    when(collection.rosterKnown("이미훑음")).thenReturn(true);
    var siblings = List.of(new CharacterSibling("시드하나", "루페온", "바드", 70, new BigDecimal("1700")));
    when(details.fetchSiblings("시드하나")).thenReturn(siblings);
    when(collection.saveRoster(siblings, clock.instant())).thenReturn(3);
    when(collection.nextProfileDue(any(), any())).thenReturn(Optional.empty());
    when(collection.nextRosterDue(any())).thenReturn(Optional.empty());

    var service = service("key", true);
    assertThat(service.tick()).isEqualTo(Task.SEED);
    verify(details, times(1)).fetchSiblings("시드하나");
    verify(details, never()).fetchSiblings("제외이름");
    verify(details, never()).fetchSiblings("이미훑음");
    assertThat(seeds.pendingCount()).isZero();
    // 같은 이름은 한 번만(멱등). 시드가 끝나면 기존 수집으로 돌아간다.
    assertThat(service.tick()).isEqualTo(Task.NONE);
    verify(details, times(1)).fetchSiblings(any());
    assertThat(registry.get("loakraken.stats.seed").tag("outcome", "skipped").counter().count())
        .isEqualTo(2);
    assertThat(registry.get("loakraken.stats.seed").tag("outcome", "success").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("loakraken.stats.collector.discovered").counter().count()).isEqualTo(3);
    assertThat(registry.get("loakraken.stats.seed.pending").gauge().value()).isZero();
  }

  @Test
  void 시드가_없는_캐릭터면_다시_부르지_않고_한도_초과면_다음_회차에_다시_부른다() {
    seeds = new CharacterSeedList(new CharacterSeedPolicy(true, List.of("한도", "없음"), ""));
    when(details.fetchSiblings("한도"))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED))
        .thenReturn(List.of());
    when(details.fetchSiblings("없음"))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    var service = service("key", true);
    assertThat(service.tick()).isEqualTo(Task.SEED);
    assertThat(seeds.pendingCount()).isEqualTo(2);
    assertThat(service.tick()).isEqualTo(Task.SEED);
    assertThat(seeds.pendingCount()).isEqualTo(1);
    assertThat(service.tick()).isEqualTo(Task.SEED);
    assertThat(seeds.pendingCount()).isZero();
    verify(details, times(1)).fetchSiblings("없음");
    assertThat(registry.get("loakraken.stats.seed").tag("outcome", "failed").counter().count())
        .isEqualTo(1);
  }

  @Test
  void 시드_목록은_공백과_중복을_정리하고_형식이_틀린_줄은_뺀다(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
      throws Exception {
    var file = dir.resolve("seeds.txt");
    java.nio.file.Files.writeString(file, "# 주석\n\n파일이름\n 가나다 \n이름 띄움\n");
    var list =
        new CharacterSeedList(
            new CharacterSeedPolicy(true, List.of("가나다", " ", "설정이름"), file.toString()));
    assertThat(list.pendingCount()).isEqualTo(3);
    assertThat(list.invalidCount()).isEqualTo(1);
    assertThat(list.peek()).isEqualTo("가나다");
    // 꺼져 있으면 아무 이름도 읽지 않는다. 파일이 없어도 앱은 뜬다.
    assertThat(
            new CharacterSeedList(new CharacterSeedPolicy(false, List.of("가나다"), ""))
                .pendingCount())
        .isZero();
    assertThat(
            new CharacterSeedList(
                    new CharacterSeedPolicy(true, List.of(), dir.resolve("없음.txt").toString()))
                .pendingCount())
        .isZero();
  }

  @Test
  void 시드와_프로필_갱신을_번갈아_한다() {
    // #271: 시드가 수천 줄이어도 그동안 전투력 표본(프로필 갱신)이 계속 쌓여야 한다.
    seeds = new CharacterSeedList(new CharacterSeedPolicy(true, List.of("시드하나", "시드둘"), ""));
    when(details.fetchSiblings(anyString())).thenReturn(List.of());
    when(collection.nextProfileDue(any(), any())).thenReturn(Optional.of(target));
    when(details.fetchArmory("모험가"))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    var service = service("key", true);
    assertThat(service.tick()).isEqualTo(Task.SEED);
    assertThat(service.tick()).isEqualTo(Task.PROFILE);
    assertThat(service.tick()).isEqualTo(Task.SEED);
    assertThat(seeds.pendingCount()).isZero();
    // 시드가 끝나면 기존 수집만 한다.
    assertThat(service.tick()).isNotEqualTo(Task.SEED);
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
        details,
        collection,
        history,
        cards,
        policy,
        seeds,
        stored,
        gate,
        lease,
        lostArk,
        registry,
        clock);
  }
}
