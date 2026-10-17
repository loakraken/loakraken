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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 전투정보실 페이지 정보 서비스(#531·#532): 캐시 쓰기 실패, single-flight 안 캐시 재확인, 생활 스킬·스킬 코드가 페이지 GET 하나를 함께 씀. 실제
 * 공식 요청 0.
 */
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

  private static final String HEX = "AB".repeat(64);
  private static final ArmoryPagePort.Identity IDENTITY =
      new ArmoryPagePort.Identity("1", "2", "3");

  @Test
  void 코드용_GET이_실패해도_생활_스킬_성공_캐시는_그대로다() {
    cached("타");
    when(pages.fetch("타"))
        .thenReturn(new ArmoryPagePort.Page(List.of(new ArmoryPagePort.LifeSkill("수렵", 5))))
        .thenThrow(new ArmoryPageFetchException(ArmoryPageFetchException.Kind.HTTP_ERROR, 500));
    assertThat(service.page("타").lifeSkills()).hasSize(1);
    assertThat(service.skillCode("타").skillCode()).isNull();
    assertThat(store.entries.get("타").failure()).isFalse();
    assertThat(service.page("타").lifeSkills()).extracting(ArmoryPagePort.LifeSkill::level).containsExactly(5);
    assertThat(store.codes.get("타").failure()).isTrue();
    verify(pages, times(2)).fetch("타");
  }

  @Test
  void 페이지가_실패로_캐시돼_있으면_코드용_GET도_하지_않는다() {
    cached("파");
    store.entries.put("파", ArmoryPageStorePort.Entry.failed());
    assertThat(service.skillCode("파").skillCode()).isNull();
    verify(pages, never()).fetch(anyString());
    // 요청하지 않은 것은 캐시하지 않는다(페이지 실패 캐시가 끝나면 바로 읽음).
    assertThat(store.codes).doesNotContainKey("파");
  }

  @Test
  void 스킬_코드는_페이지_GET_하나로_생활_스킬_캐시도_채운다() {
    cached("라");
    when(pages.fetch("라"))
        .thenReturn(
            new ArmoryPagePort.Page(List.of(new ArmoryPagePort.LifeSkill("채집", 10)), IDENTITY));
    when(pages.skillCode(IDENTITY)).thenReturn(HEX);
    assertThat(service.skillCode("라").skillCode()).isEqualTo(HEX);
    assertThat(store.entries.get("라").lifeSkills())
        .extracting(ArmoryPagePort.LifeSkill::level)
        .containsExactly(10);
    // 생활 스킬은 캐시에서, 스킬 코드도 캐시에서.
    assertThat(service.page("라").lifeSkills()).hasSize(1);
    assertThat(service.skillCode("라").skillCode()).isEqualTo(HEX);
    verify(pages, times(1)).fetch("라");
    verify(pages, times(1)).skillCode(IDENTITY);
  }

  @Test
  void 생활_스킬과_스킬_코드를_동시에_원하면_페이지_GET은_한_번이다() throws Exception {
    cached("마");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(pages.fetch("마"))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return new ArmoryPagePort.Page(
                  List.of(new ArmoryPagePort.LifeSkill("낚시", 3)), IDENTITY);
            });
    when(pages.skillCode(IDENTITY)).thenReturn(HEX);
    var pageResult = new AtomicReference<ArmoryPageService.View>();
    var codeResult = new AtomicReference<ArmoryPageService.CodeView>();
    var a = new Thread(() -> pageResult.set(service.page("마")));
    a.start();
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    var b = new Thread(() -> codeResult.set(service.skillCode("마")));
    b.start();
    // B가 A의 페이지 GET을 기다리기 시작할 때까지(CompletableFuture.join에서 멈춤).
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (b.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
      Thread.onSpinWait();
    release.countDown();
    a.join(5_000);
    b.join(5_000);
    assertThat(pageResult.get().lifeSkills()).hasSize(1);
    assertThat(codeResult.get().skillCode()).isEqualTo(HEX);
    verify(pages, times(1)).fetch("마");
  }

  @Test
  void 페이지에_식별_값이_없으면_코드_형식_실패로_세고_3번이면_코드만_멈춘다() {
    for (var name : List.of("바", "사", "아")) {
      cached(name);
      when(pages.fetch(name)).thenReturn(new ArmoryPagePort.Page(List.of()));
      assertThat(service.skillCode(name).skillCode()).isNull();
      assertThat(store.codes.get(name).failure()).isTrue();
    }
    assertThat(guard.skillCodeStopped()).isTrue();
    assertThat(guard.stopped()).isFalse();
    verify(pages, never()).skillCode(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void 스킬_코드_캐시를_쓰지_못하면_모든_요청을_쉰다() {
    cached("자");
    cached("차");
    when(pages.fetch("자"))
        .thenReturn(
            new ArmoryPagePort.Page(List.of(new ArmoryPagePort.LifeSkill("벌목", 1)), IDENTITY));
    when(pages.skillCode(IDENTITY)).thenReturn(HEX);
    store.codeWritable = false;
    assertThat(service.skillCode("자").skillCode()).isEqualTo(HEX);
    assertThat(service.page("차").lifeSkills()).isNull();
    verify(pages, never()).fetch("차");
  }

  private static final class FakeStore implements ArmoryPageStorePort {
    volatile boolean writable = true;
    volatile boolean codeWritable = true;
    final Map<String, Entry> entries = new ConcurrentHashMap<>();
    final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
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

    @Override
    public Optional<CodeEntry> findCode(String characterName) {
      return Optional.ofNullable(codes.get(characterName));
    }

    @Override
    public boolean saveCode(String characterName, CodeEntry entry, Duration ttl) {
      if (!codeWritable) return false;
      codes.put(characterName, entry);
      return true;
    }
  }
}
