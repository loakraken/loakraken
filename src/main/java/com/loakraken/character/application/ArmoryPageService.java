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
  private final SingleFlight<ArmoryPageStorePort.CodeEntry> codeFlights = new SingleFlight<>();

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

  /** 스킬 코드 응답 값(#532). enabled가 false면 나머지는 null, 읽지 못했으면 enabled true에 나머지 null. */
  public record CodeView(String characterName, boolean enabled, Instant fetchedAt, String skillCode) {}

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
   * 스킬 코드(#532). 웹이 스킬 탭에 들어갈 때 부른다. 캐시가 없으면 페이지 GET(식별 값·생활 스킬)과 코드 POST로 공식 요청이 2번 들고, 그 페이지로
   * 생활 스킬 캐시도 함께 갱신한다. 식별 값은 이 메서드 안 메모리에서만 쓴다. 스위치·멈춤은 페이지 정보와 따로다.
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

  private ArmoryPageStorePort.CodeEntry loadCode(String official) {
    if (guard.skillCodeStopped()) {
      codeSkipped("stopped");
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    var decision = guard.acquire();
    if (decision != ArmoryPageGuard.Decision.ALLOWED) {
      codeSkipped(decision.name().toLowerCase(java.util.Locale.ROOT));
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    ArmoryPagePort.Page page;
    var pageSample = Timer.start(registry);
    try {
      page = pages.fetch(official);
      guard.success();
      fetched("page", "ok", pageSample);
      store.save(
          official,
          new ArmoryPageStorePort.Entry(clock.instant(), page.lifeSkills()),
          policy.cacheTtl());
    } catch (ArmoryPageFetchException exception) {
      guard.failure(exception.kind());
      fetched("page", exception.kind().label(), pageSample);
      return codeFailed(official, "page", exception);
    }
    if (page.identity() == null) {
      // 식별 값이 페이지에 없으면 코드 요청을 할 수 없다(구조 변경으로 센다).
      var missing =
          new ArmoryPageFetchException(ArmoryPageFetchException.Kind.PARSE_ERROR, null);
      guard.skillCodeFailure(missing.kind());
      registry
          .counter("loakraken.armory_page.fetch", "phase", "code", "result", "parse_error")
          .increment();
      return codeFailed(official, "code", missing);
    }
    decision = guard.acquire();
    if (decision != ArmoryPageGuard.Decision.ALLOWED) {
      codeSkipped(decision.name().toLowerCase(java.util.Locale.ROOT));
      return ArmoryPageStorePort.CodeEntry.failed();
    }
    var codeSample = Timer.start(registry);
    try {
      var code = pages.skillCode(page.identity());
      guard.skillCodeSuccess();
      fetched("code", "ok", codeSample);
      var entry = new ArmoryPageStorePort.CodeEntry(clock.instant(), code);
      store.saveCode(official, entry, policy.cacheTtl());
      return entry;
    } catch (ArmoryPageFetchException exception) {
      guard.skillCodeFailure(exception.kind());
      fetched("code", exception.kind().label(), codeSample);
      return codeFailed(official, "code", exception);
    }
  }

  private ArmoryPageStorePort.CodeEntry codeFailed(
      String official, String phase, ArmoryPageFetchException exception) {
    log.warn(
        "event=armory_page_fetch_failed phase={} result={} status={}",
        phase,
        exception.kind().label(),
        exception.status());
    var failed = ArmoryPageStorePort.CodeEntry.failed();
    store.saveCode(official, failed, policy.failureTtl());
    return failed;
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

  private ArmoryPageStorePort.Entry load(String official) {
    var decision = guard.acquire();
    if (decision != ArmoryPageGuard.Decision.ALLOWED) {
      // 요청하지 않은 것은 캐시하지 않는다(장치가 풀리면 바로 읽음).
      skipped(decision.name().toLowerCase(java.util.Locale.ROOT));
      return ArmoryPageStorePort.Entry.failed();
    }
    var sample = Timer.start(registry);
    try {
      var page = pages.fetch(official);
      guard.success();
      fetched("page", "ok", sample);
      var entry = new ArmoryPageStorePort.Entry(clock.instant(), page.lifeSkills());
      store.save(official, entry, policy.cacheTtl());
      return entry;
    } catch (ArmoryPageFetchException exception) {
      guard.failure(exception.kind());
      fetched("page", exception.kind().label(), sample);
      log.warn(
          "event=armory_page_fetch_failed phase=page result={} status={}",
          exception.kind().label(),
          exception.status());
      var failed = ArmoryPageStorePort.Entry.failed();
      store.save(official, failed, policy.failureTtl());
      return failed;
    }
  }

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
