package com.loakraken.character.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 공식 전투정보실 페이지 정보(#531): 생활 스킬 레벨. 공식 Open API에 없는 값만 공식 전투정보실 캐릭터 페이지에서 읽는다.
 *
 * <p>사용자가 검색한 캐릭터(우리 캐릭터 캐시에 있음)만 읽고, 뽑은 값만 캐시한다. 처리정지 목록 캐릭터는 요청하지 않고 읽기 실패와 같은 응답을 준다(목록에 있는지
 * 드러내지 않음). 요청 간격·쉬기·멈춤은 {@link ArmoryPageGuard}가 맡는다. 로그·지표에 캐릭터 이름을 남기지 않는다.
 */
@Service
public class ArmoryPageService {
  public static final String SOURCE = "공식 전투정보실";
  private static final Logger log = LoggerFactory.getLogger(ArmoryPageService.class);

  /** 응답 값. enabled가 false면 나머지는 null, 읽지 못했으면 enabled true에 나머지 null. */
  public record View(
      String characterName,
      boolean enabled,
      String source,
      Instant fetchedAt,
      List<ArmoryPagePort.LifeSkill> lifeSkills) {}

  private final ArmoryPagePolicy policy;
  private final CharacterCache cache;
  private final CharacterStopList stopList;
  private final ArmoryPageStorePort store;
  private final ArmoryPagePort pages;
  private final ArmoryPageGuard guard;
  private final MeterRegistry registry;
  private final Clock clock;
  private final SingleFlight<ArmoryPageStorePort.Entry> flights = new SingleFlight<>();

  public ArmoryPageService(
      ArmoryPagePolicy policy,
      CharacterCache cache,
      CharacterStopList stopList,
      ArmoryPageStorePort store,
      ArmoryPagePort pages,
      ArmoryPageGuard guard,
      MeterRegistry registry,
      Clock clock) {
    this.policy = policy;
    this.cache = cache;
    this.stopList = stopList;
    this.store = store;
    this.pages = pages;
    this.guard = guard;
    this.registry = registry;
    this.clock = clock;
  }

  public View page(String characterName) {
    var name = new SearchCharacterCommand(characterName).characterName();
    if (!policy.enabled()) {
      skipped("disabled");
      return new View(name, false, null, null, null);
    }
    CharacterResult result;
    try {
      result = cache.find(name).or(() -> cache.findArmory(name)).orElse(null);
    } catch (CharacterException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
    if (result == null) {
      skipped("not_cached");
      throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_CACHED);
    }
    var profile = result.details().profile();
    var official = profile.characterName();
    // 처리정지 목록이거나 확인하지 못하면 요청하지 않는다. 응답은 읽기 실패와 같다(B5).
    if (!stopList.recordable(official, profile.serverName())) {
      skipped("suspended");
      return failed(official);
    }
    ArmoryPageStorePort.Entry entry;
    try {
      entry = store.find(official).orElse(null);
    } catch (CharacterException exception) {
      // 캐시를 확인할 수 없으면 같은 캐릭터를 거듭 부를 수 있어 요청하지 않는다.
      skipped("cache_unavailable");
      return failed(official);
    }
    if (entry == null) entry = flights.run(official, () -> load(official));
    return entry.failure()
        ? failed(official)
        : new View(official, true, SOURCE, entry.fetchedAt(), entry.lifeSkills());
  }

  private ArmoryPageStorePort.Entry load(String official) {
    // 같은 이름을 먼저 받은 요청이 방금 캐시했을 수 있다(single-flight 안에서 다시 확인).
    try {
      var cached = store.find(official);
      if (cached.isPresent()) return cached.get();
    } catch (CharacterException exception) {
      skipped("cache_unavailable");
      return ArmoryPageStorePort.Entry.failed();
    }
    var permit = guard.acquire();
    if (!permit.allowed()) {
      // 요청하지 않은 것은 캐시하지 않는다(장치가 풀리면 바로 읽음).
      skipped(permit.decision().name().toLowerCase(java.util.Locale.ROOT));
      return ArmoryPageStorePort.Entry.failed();
    }
    var sample = Timer.start(registry);
    ArmoryPagePort.Page page;
    try {
      page = pages.fetch(official);
    } catch (ArmoryPageFetchException exception) {
      guard.failure(permit, exception.kind());
      fetched(exception.kind().label(), sample);
      log.warn(
          "event=armory_page_fetch_failed phase=page result={} status={}",
          exception.kind().label(),
          exception.status());
      var failed = ArmoryPageStorePort.Entry.failed();
      save(official, failed, policy.failureTtl());
      return failed;
    }
    guard.success(permit);
    fetched("ok", sample);
    var entry = new ArmoryPageStorePort.Entry(clock.instant(), page.lifeSkills());
    save(official, entry, policy.cacheTtl());
    return entry;
  }

  /** 캐시를 쓰지 못하면 실패 캐시 시간만큼 모든 요청을 쉰다(보호 캐시 없이 같은 캐릭터를 거듭 부르지 않게). */
  private void save(String official, ArmoryPageStorePort.Entry entry, java.time.Duration ttl) {
    if (!store.save(official, entry, ttl)) guard.pause(policy.failureTtl(), "cache_write");
  }

  private View failed(String official) {
    return new View(official, true, null, null, null);
  }

  private void fetched(String result, Timer.Sample sample) {
    registry.counter("loakraken.armory_page.fetch", "phase", "page", "result", result).increment();
    sample.stop(
        Timer.builder("loakraken.armory_page.fetch.duration")
            .tag("phase", "page")
            .publishPercentileHistogram()
            .register(registry));
  }

  private void skipped(String reason) {
    registry.counter("loakraken.armory_page.skipped", "reason", reason).increment();
  }
}
