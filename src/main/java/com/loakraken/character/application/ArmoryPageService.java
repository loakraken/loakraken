package com.loakraken.character.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 공식 전투정보실 페이지 정보(#531·#532): 생활 스킬 레벨과 스킬 코드. 공식 Open API에 없는 값만 공식 전투정보실 캐릭터 페이지에서 읽는다.
 *
 * <p>사용자가 검색한 캐릭터(우리 캐릭터 캐시에 있음)만 읽고, 뽑은 값만 캐시한다. 처리정지 목록 캐릭터는 요청하지 않고 읽기 실패와 같은 응답을 준다(목록에 있는지
 * 드러내지 않음). 요청 간격·쉬기·멈춤은 {@link ArmoryPageGuard} 하나가 페이지·코드 모두 맡는다. 같은 캐릭터의 페이지 GET은 생활 스킬과 스킬 코드
 * 요청이 함께 기다린다(single-flight 하나). 로그·지표에 캐릭터 이름을 남기지 않는다.
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

  /** 스킬 코드 응답 값(#532). enabled가 false면 나머지는 null, 읽지 못했으면 enabled true에 나머지 null. */
  public record CodeView(
      String characterName, boolean enabled, Instant fetchedAt, String skillCode) {}

  /** 페이지 GET 한 번의 결과. page가 null이면 요청하지 않았거나(skipped) 실패. 식별 값은 메모리에서만 쓴다. */
  private record PageFetch(
      ArmoryPagePort.Page page, ArmoryPageStorePort.Entry entry, String skipped) {}

  private final ArmoryPagePolicy policy;
  private final CharacterCache cache;
  private final CharacterStopList stopList;
  private final ArmoryPageStorePort store;
  private final ArmoryPagePort pages;
  private final ArmoryPageGuard guard;
  private final MeterRegistry registry;
  private final Clock clock;
  private final SingleFlight<ArmoryPageStorePort.Entry> flights = new SingleFlight<>();
  private final SingleFlight<ArmoryPageStorePort.CodeEntry> codeFlights = new SingleFlight<>();
  private final SingleFlight<PageFetch> pageFetches = new SingleFlight<>();

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
    var target = target(name);
    var official = target.official();
    if (!target.recordable()) {
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

  /**
   * 스킬 코드(#532). 웹이 스킬 탭에 들어갈 때 부른다. 캐시가 없으면 페이지 GET(식별 값·생활 스킬)과 코드 POST로 공식 요청이 2번 들고, 그 페이지로 생활
   * 스킬 캐시도 함께 갱신한다. 식별 값은 캐시하지 않는다(PM 결정: 캐릭터당 6시간에 GET 1번 더). 스위치는 페이지 정보와 따로, 간격·쉬기·멈춤은 같은 장치다.
   */
  public CodeView skillCode(String characterName) {
    var name = new SearchCharacterCommand(characterName).characterName();
    if (!policy.enabled() || !policy.skillCode().enabled()) {
      codeSkipped("disabled");
      return new CodeView(name, false, null, null);
    }
    var target = target(name);
    var official = target.official();
    if (!target.recordable()) {
      codeSkipped("suspended");
      return new CodeView(official, true, null, null);
    }
    ArmoryPageStorePort.CodeEntry entry;
    try {
      entry = store.findCode(official).orElse(null);
    } catch (CharacterException exception) {
      codeSkipped("cache_unavailable");
      return new CodeView(official, true, null, null);
    }
    if (entry == null) entry = codeFlights.run(official, () -> loadCode(official));
    return entry.failure()
        ? new CodeView(official, true, null, null)
        : new CodeView(official, true, entry.fetchedAt(), entry.skillCode());
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
    var fetch = fetchPage(official);
    if (fetch.skipped() != null) skipped(fetch.skipped());
    return fetch.entry();
  }

  private ArmoryPageStorePort.CodeEntry loadCode(String official) {
    try {
      var cached = store.findCode(official);
      if (cached.isPresent()) return cached.get();
    } catch (CharacterException exception) {
      codeSkipped("cache_unavailable");
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    // 코드만 멈췄으면 페이지 GET도 하지 않는다(식별 값을 쓸 데가 없음).
    if (guard.skillCodeStopped()) {
      codeSkipped("stopped");
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    // 페이지가 실패로 캐시돼 있으면(404 등) 실패 캐시 시간 동안 코드용 GET도 하지 않는다.
    ArmoryPageStorePort.Entry cachedPage;
    try {
      cachedPage = store.find(official).orElse(null);
    } catch (CharacterException exception) {
      codeSkipped("cache_unavailable");
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    if (cachedPage != null && cachedPage.failure()) {
      codeSkipped("page_failed");
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    var fetch = fetchPage(official, cachedPage);
    if (fetch.skipped() != null) {
      // 요청하지 않은 것은 캐시하지 않는다(장치가 풀리면 바로 읽음).
      codeSkipped(fetch.skipped());
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    if (fetch.page() == null) return codeFailed(official);
    var identity = fetch.page().identity();
    if (identity == null) {
      // 식별 값이 페이지에 없으면 코드 요청을 할 수 없다(코드 쪽 구조 변경으로 센다).
      guard.structureFailure(ArmoryPageGuard.Phase.CODE);
      registry
          .counter("loakraken.armory_page.fetch", "phase", "code", "result", "parse_error")
          .increment();
      log.warn("event=armory_page_fetch_failed phase=code result=parse_error status=null");
      return codeFailed(official);
    }
    var permit = guard.acquire(ArmoryPageGuard.Phase.CODE);
    if (!permit.allowed()) {
      codeSkipped(permit.decision().name().toLowerCase(Locale.ROOT));
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    var sample = Timer.start(registry);
    String code;
    try {
      code = pages.skillCode(identity);
    } catch (ArmoryPageFetchException exception) {
      guard.failure(permit, exception.kind());
      fetched("code", exception.kind().label(), sample);
      log.warn(
          "event=armory_page_fetch_failed phase=code result={} status={}",
          exception.kind().label(),
          exception.status());
      return codeFailed(official);
    }
    guard.success(permit);
    fetched("code", "ok", sample);
    var entry = new ArmoryPageStorePort.CodeEntry(clock.instant(), code);
    if (!store.saveCode(official, entry, policy.cacheTtl())) cacheWriteFailed();
    return entry;
  }

  /**
   * 페이지 GET 한 번. 같은 캐릭터를 생활 스킬·스킬 코드가 동시에 원하면 한 번만 부른다. 결과는 생활 스킬 캐시에 저장한다(실패는 실패 캐시 시간). 직전에 캐시된
   * 값이 있어도 스킬 코드는 식별 값이 필요해 다시 부른다. 그때 GET이 실패하면 이미 있는 생활 스킬 성공 캐시를 실패로 덮지 않는다({@code
   * cachedSuccess}를 그대로 돌려줌).
   */
  private PageFetch fetchPage(String official) {
    return fetchPage(official, null);
  }

  private PageFetch fetchPage(String official, ArmoryPageStorePort.Entry cachedSuccess) {
    return pageFetches.run(
        official,
        () -> {
          var permit = guard.acquire(ArmoryPageGuard.Phase.PAGE);
          if (!permit.allowed()) {
            return new PageFetch(
                null,
                ArmoryPageStorePort.Entry.failed(),
                permit.decision().name().toLowerCase(Locale.ROOT));
          }
          var sample = Timer.start(registry);
          ArmoryPagePort.Page page;
          try {
            page = pages.fetch(official);
          } catch (ArmoryPageFetchException exception) {
            guard.failure(permit, exception.kind());
            fetched("page", exception.kind().label(), sample);
            log.warn(
                "event=armory_page_fetch_failed phase=page result={} status={}",
                exception.kind().label(),
                exception.status());
            if (cachedSuccess != null && !cachedSuccess.failure())
              return new PageFetch(null, cachedSuccess, null);
            var failed = ArmoryPageStorePort.Entry.failed();
            save(official, failed, policy.failureTtl());
            return new PageFetch(null, failed, null);
          }
          guard.success(permit);
          fetched("page", "ok", sample);
          var entry = new ArmoryPageStorePort.Entry(clock.instant(), page.lifeSkills());
          save(official, entry, policy.cacheTtl());
          return new PageFetch(page, entry, null);
        });
  }

  private ArmoryPageStorePort.CodeEntry codeFailed(String official) {
    var failed = ArmoryPageStorePort.CodeEntry.failed();
    if (!store.saveCode(official, failed, policy.failureTtl())) cacheWriteFailed();
    return failed;
  }

  /** 캐시를 쓰지 못하면 실패 캐시 시간만큼 모든 요청을 쉰다(보호 캐시 없이 같은 캐릭터를 거듭 부르지 않게). */
  private void save(String official, ArmoryPageStorePort.Entry entry, Duration ttl) {
    if (!store.save(official, entry, ttl)) cacheWriteFailed();
  }

  private void cacheWriteFailed() {
    guard.pause(policy.failureTtl(), "cache_write");
  }

  /** 우리 캐릭터 캐시에서 공식 이름·처리정지 여부를 찾는다. 캐시에 없으면 404, 캐시 장애면 503. */
  private Target target(String name) {
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
    // 처리정지 목록이거나 확인하지 못하면 요청하지 않는다. 응답은 읽기 실패와 같다(B5).
    return new Target(
        profile.characterName(),
        stopList.recordable(profile.characterName(), profile.serverName()));
  }

  private record Target(String official, boolean recordable) {}

  private View failed(String official) {
    return new View(official, true, null, null, null);
  }

  private void fetched(String phase, String result, Timer.Sample sample) {
    registry.counter("loakraken.armory_page.fetch", "phase", phase, "result", result).increment();
    sample.stop(
        Timer.builder("loakraken.armory_page.fetch.duration")
            .tag("phase", phase)
            .publishPercentileHistogram()
            .register(registry));
  }

  private void skipped(String reason) {
    registry.counter("loakraken.armory_page.skipped", "reason", reason).increment();
  }

  private void codeSkipped(String reason) {
    registry.counter("loakraken.armory_page.skill_code.skipped", "reason", reason).increment();
  }
}
