package com.loakraken.specup.application;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerReasons;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.specup.domain.SpecUpCost;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 스펙업 목표 경로(L31). 목표 전투력에 닿을 때까지 지금 상태에서 칸마다 '다음 한 단계'를 만들고, 효율 순(1만 골드당 전투력, 비용을 모르면 그 뒤에 Δ 순)으로
 * 가장 앞의 한 단계를 적용하기를 되풀이한다. 같은 칸을 여러 단계 올릴 수 있다. 모든 전투력은 시뮬레이터 결과이고(합산·추정 없음), 공식 API를 부르지 않는다.
 *
 * <p>비용: 시세가 없는 단계가 하나라도 있으면 그 단계부터 누적 비용은 null이다(0으로 더하지 않음). 보석 같은 칸의 둘째 단계부터는 직전 단계 매입가를 뺀
 * 증분이다(PM-백엔드 L31 판정 ①).
 */
@Service
public class SpecUpGoalService {
  /**
   * v3: 목표 경로도 추천 순서와 같은 단계 종류(어빌리티 스톤 목표 조합, 완갑 스위치)를 고른다(계산 검증 11-09: 스톤으로만 닿는 목표가 '더 올릴 수
   * 없음'이었다).
   */
  public static final String RULE_VERSION = "spec-up-goal-v5";

  static final int MAX_STEPS = 30;
  static final Duration TIME_LIMIT = Duration.ofSeconds(2);
  static final Duration CACHE_TTL = Duration.ofMinutes(10);
  static final String REACHED = "REACHED";
  static final String ALREADY_REACHED = "ALREADY_REACHED";
  static final String NO_CANDIDATE = "NO_CANDIDATE";
  static final String STEP_LIMIT = "STEP_LIMIT";
  static final String TIME_LIMITED = "TIME_LIMIT";
  private static final Map<String, String> STOP_TEXT =
      Map.of(
          REACHED, "목표 전투력에 닿았어요.",
          ALREADY_REACHED, "이미 목표 전투력 이상이에요.",
          NO_CANDIDATE, "더 올릴 수 있는 항목이 없어 여기서 멈췄어요.",
          STEP_LIMIT, "단계가 너무 많아 30단계에서 멈췄어요. 목표를 낮춰 보세요.",
          TIME_LIMITED, "계산 시간이 길어 여기까지 고른 단계만 보여 드려요.");

  private final SpecUpService specUp;
  private final CombatPowerSimulationService simulator;
  private final MarketAuctionService auctions;
  private final RelicBookPriceService books;
  private final RefiningMaterialPriceService materials;
  private final SpecUpGoalStorePort store;
  private final LongSupplier nanoTime;

  /**
   * 추천 순서·목표 경로 계산(캐시에 없을 때)이 함께 쓰는 자리. 운영 서버(4GB)에서 무거운 계산이 SOLVE_MAX_CONCURRENT개를 넘지 않게 한다(PM 총괄
   * 11-13). 넘으면 기다리지 않고 429 SPEC_UP_BUSY(요청 스레드를 붙잡지 않게).
   */
  private final java.util.concurrent.Semaphore solves;

  /**
   * 계산 자리를 쥔 클라이언트 키(보안 11-16 강화 (a)): 한 클라이언트는 자리를 하나만 쥔다(둘 다 차지해 다른 사용자를 막지 못하게). 이미 쥐고 있으면 기다리지
   * 않고 429 SPEC_UP_BUSY. 같은 계산에 합류(single)하는 요청은 자리를 쓰지 않는다. 클라이언트 키가 없는 요청(유입 제한 꺼짐·내부 호출)은 전체 자리만
   * 센다.
   */
  private final java.util.Set<String> seatHolders = ConcurrentHashMap.newKeySet();

  private final java.util.function.Supplier<String> client;

  private final ConcurrentHashMap<String, CompletableFuture<Object>> flights =
      new ConcurrentHashMap<>();

  @Autowired
  public SpecUpGoalService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      SpecUpGoalStorePort store,
      MeterRegistry meters) {
    this(specUp, simulator, auctions, books, materials, store, meters, System::nanoTime);
  }

  SpecUpGoalService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      SpecUpGoalStorePort store,
      MeterRegistry meters,
      LongSupplier nanoTime) {
    this(
        specUp,
        simulator,
        auctions,
        books,
        materials,
        store,
        meters,
        nanoTime,
        com.loakraken.common.lostark.ClientLimiter::currentClient);
  }

  SpecUpGoalService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      SpecUpGoalStorePort store,
      MeterRegistry meters,
      LongSupplier nanoTime,
      java.util.function.Supplier<String> client) {
    this.client = client;
    this.specUp = specUp;
    this.simulator = simulator;
    this.auctions = auctions;
    this.books = books;
    this.materials = materials;
    this.store = store;
    this.nanoTime = nanoTime;
    this.meters = meters;
    this.solves = new java.util.concurrent.Semaphore(SOLVE_MAX_CONCURRENT);
  }

  private static final Logger log = LoggerFactory.getLogger(SpecUpGoalService.class);

  /**
   * 추천 순서 운영 지표. loakraken.spec_up.path{outcome=cache_hit|computed|busy|client_busy}: 캐시 적중·계산·자리
   * 부족(429)·클라이언트가 이미 자리를 쥠(429) 비율. loakraken.spec_up.path_compute{pick, stop}: 캐시에 없을 때 계산
   * 시간(p95·p99), stop은 멈춘 이유 코드(작은 고정 집합). 캐릭터 이름은 태그·로그에 넣지 않는다.
   */
  private final MeterRegistry meters;

  private void pathOutcome(String outcome) {
    meters.counter("loakraken.spec_up.path", "outcome", outcome).increment();
  }

  /** 자리를 못 받은 이유(지표 outcome 값). */
  private enum SeatRefusal {
    /** 그 클라이언트가 이미 자리를 쥐고 있다. */
    CLIENT_BUSY("client_busy"),
    /** 전체 자리가 모두 찼다. */
    FULL("busy");

    final String outcome;

    SeatRefusal(String outcome) {
      this.outcome = outcome;
    }
  }

  /**
   * 계산 자리를 받는다. 받으면 null, 못 받으면 이유. holder가 null이면(클라이언트 키 없음) 전체 자리만 센다. 자리는 주소 키로 센다: 검색봇
   * 표시('bot:' 접두)는 User-Agent만 바꿔 붙일 수 있어 같은 주소의 두 번째 자리가 되지 않게 뗀다.
   */
  private SeatRefusal takeSeat(String holder) {
    var seat = com.loakraken.common.lostark.ClientLimiter.addressKey(holder);
    if (seat != null && !seatHolders.add(seat)) return SeatRefusal.CLIENT_BUSY;
    if (solves.tryAcquire()) return null;
    if (seat != null) seatHolders.remove(seat);
    return SeatRefusal.FULL;
  }

  // 거절 로그의 client는 유입 제한 거절 로그와 같은 짧은 키(HMAC 앞 6자리, 주소 원문 아님): 한 키가 client_busy를 몰아 내는지(공유 프록시·남용)
  // 본다.
  private void releaseSeat(String holder) {
    solves.release();
    var seat = com.loakraken.common.lostark.ClientLimiter.addressKey(holder);
    if (seat != null) seatHolders.remove(seat);
  }

  /** 목표 경로 운영 지표 loakraken.spec_up.goal{outcome=cache_hit|computed|busy|client_busy}. */
  private void goalOutcome(String outcome) {
    meters.counter("loakraken.spec_up.goal", "outcome", outcome).increment();
  }

  // v2: 완갑(팔 보호구) 재련 단계를 고른다. v3: 어빌리티 스톤 목표 조합(각인 둘을 한 단계에), 단계 changes.
  public static final String PATH_RULE_VERSION = "spec-up-path-v7";
  static final int PATH_MAX_STEPS = 30;
  static final int SOLVE_MAX_CONCURRENT = 2;
  static final Duration PATH_RETRY_AFTER = Duration.ofSeconds(2);
  private static final Map<String, String> PATH_STOP_TEXT =
      Map.of(
          NO_CANDIDATE,
          "시세가 있는 항목 가운데 전투력이 오르는 것을 모두 골랐어요.",
          NO_CANDIDATE + ":KRAKEN",
          "시세가 있는 항목 가운데 점수가 오르는 것을 모두 골랐어요.",
          STEP_LIMIT,
          "정한 단계 수까지 보여 드려요.",
          TIME_LIMITED,
          "계산 시간이 길어 여기까지 고른 단계만 보여 드려요.");

  /** 순서에서 뺀 항목(지금 상태에서 시세를 몰라 고를 수 없는 다음 단계 중 전투력이 오르는 것). 추측 비용을 넣지 않는다. */
  public record Excluded(
      String candidateId,
      String kind,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      BigDecimal deltaCombatPower,
      SpecUpCost cost) {}

  /** 추천 순서(이어 고르기): 목표 없이 limit 단계까지. steps는 시세가 있는 단계만, unpriced는 지금 상태 기준 뺀 항목. */
  public record Path(
      String characterName,
      String serverName,
      String className,
      BigDecimal combatPower,
      BigDecimal finalCombatPower,
      String pick,
      int limit,
      String stopReasonCode,
      String stopReason,
      Long totalCost,
      String ruleVersion,
      String simulatorRuleVersion,
      Instant fetchedAt,
      Instant pricesAt,
      List<Step> steps,
      List<Excluded> unpriced,
      BigDecimal krakenScore,
      BigDecimal finalKrakenScore,
      String krakenSortUnavailableReason,
      Boolean supportOrderAvailable,
      String krakenScoreRole) {
    /** supportOrderAvailable 없이(경로를 만드는 곳에서 따로 채운다). */
    Path(
        String characterName,
        String serverName,
        String className,
        BigDecimal combatPower,
        BigDecimal finalCombatPower,
        String pick,
        int limit,
        String stopReasonCode,
        String stopReason,
        Long totalCost,
        String ruleVersion,
        String simulatorRuleVersion,
        Instant fetchedAt,
        Instant pricesAt,
        List<Step> steps,
        List<Excluded> unpriced,
        BigDecimal krakenScore,
        BigDecimal finalKrakenScore,
        String krakenSortUnavailableReason) {
      this(
          characterName,
          serverName,
          className,
          combatPower,
          finalCombatPower,
          pick,
          limit,
          stopReasonCode,
          stopReason,
          totalCost,
          ruleVersion,
          simulatorRuleVersion,
          fetchedAt,
          pricesAt,
          steps,
          unpriced,
          krakenScore,
          finalKrakenScore,
          krakenSortUnavailableReason,
          null,
          null);
    }

    /**
     * 비용을 아는 단계의 단계 비용(extraCost) 합(PM 총괄 11-19). 비용 모름 단계를 이으면 totalCost는 모름(null)이라, 웹은 이 값과
     * unknownCostSteps로 'X G + 비용 모름 N단계'를 보인다. 단계가 없으면 0.
     */
    public long knownCostTotal() {
      long sum = 0;
      for (var step : steps) if (step.extraCost() != null) sum += step.extraCost();
      return sum;
    }

    /** 단계 비용(extraCost)을 모르는 단계 수(PM 총괄 11-19). DELTA는 비용 모름 단계를 고르지 않아 늘 0. */
    public int unknownCostSteps() {
      int count = 0;
      for (var step : steps) if (step.extraCost() == null) count++;
      return count;
    }

    Path withSupportOrderAvailable(boolean available, String role) {
      return new Path(
          characterName,
          serverName,
          className,
          combatPower,
          finalCombatPower,
          pick,
          limit,
          stopReasonCode,
          stopReason,
          totalCost,
          ruleVersion,
          simulatorRuleVersion,
          fetchedAt,
          pricesAt,
          steps,
          unpriced,
          krakenScore,
          finalKrakenScore,
          krakenSortUnavailableReason,
          available,
          role);
    }

    /** KRAKEN이 아닌 경로(점수 필드 없음). */
    Path(
        String characterName,
        String serverName,
        String className,
        BigDecimal combatPower,
        BigDecimal finalCombatPower,
        String pick,
        int limit,
        String stopReasonCode,
        String stopReason,
        Long totalCost,
        String ruleVersion,
        String simulatorRuleVersion,
        Instant fetchedAt,
        Instant pricesAt,
        List<Step> steps,
        List<Excluded> unpriced) {
      this(
          characterName,
          serverName,
          className,
          combatPower,
          finalCombatPower,
          pick,
          limit,
          stopReasonCode,
          stopReason,
          totalCost,
          ruleVersion,
          simulatorRuleVersion,
          fetchedAt,
          pricesAt,
          steps,
          unpriced,
          null,
          null,
          null);
    }
  }

  /**
   * 추천 순서(스펙업 순서 시안 '나'): 지금 상태에서 pick 기준으로 가장 앞의 다음 한 단계를 적용하고 다시 재기를 limit 단계까지(목표 없음). 시세(더 드는
   * 비용)를 모르는 단계는 고르지 않고 unpriced로 따로 준다. 목표 경로와 같은 단계·비용·시뮬레이터를 쓴다. 같은 캐릭터 수집 시각·시세·기준·단계 수는 10분
   * 캐시, 같은 키는 한 번만 계산, 캐시에 없는 계산은 동시에 SOLVE_MAX_CONCURRENT개까지(넘으면 바로 429).
   */
  public Path path(String characterName, Pick requested, int limit) {
    if (!specUp.enabled()) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    var snapshot = simulator.snapshot(characterName);
    // KRAKEN(PM 총괄 11-15 (d)): 스위치가 꺼졌거나 점수를 낼 수 없으면 EFFICIENCY로 되돌리고 그 이유를 준다(목록
    // krakenSortUnavailableReason과
    // 같은 값). 응답 sort는 실제로 쓴 기준이라 KRAKEN은 KRAKEN을 요청한 경로에만 나온다.
    var resolved = resolveKraken(snapshot, requested);
    var pick = resolved.pick();
    var krakenContext = resolved.context();
    var krakenUnavailable = resolved.unavailable();
    // 웹이 KRAKEN 버튼을 보일지(스위치 + 점수 문맥). 캐시와 따로 돌려줄 때마다 채운다(스위치를 바꿔도 캐시가 옛 값을 주지 않게).
    // 스위치가 켜졌을 때만 점수 문맥을 본다(꺼져 있으면 점수 계산 없음). 점수식(DEALER·SUPPORT)도 그때만.
    var orderContext =
        !specUp.supportOrderEnabled()
            ? null
            : krakenContext != null ? krakenContext : specUp.krakenContext(snapshot);
    boolean supportOrderAvailable = orderContext != null && orderContext.available();
    String krakenScoreRole = SpecUpService.krakenScoreRole(orderContext);
    var profile = snapshot.details().profile();
    var before =
        profile.combatPower() == null
            ? null
            : profile.combatPower().setScale(2, RoundingMode.HALF_UP);
    var gems = gemPrices();
    var refinePrices = materials.cached();
    var polish = specUp.polishSteps(snapshot);
    var key =
        String.join(
                ":",
                profile.serverName(),
                profile.characterName(),
                pick.name(),
                String.valueOf(limit),
                String.valueOf(
                    snapshot.fetchedAt() == null ? 0 : snapshot.fetchedAt().toEpochMilli()),
                String.valueOf(latest(gems)),
                staleSignature(gems),
                String.valueOf(refinePrices.map(p -> p.observedAt().toEpochMilli()).orElse(0L)),
                String.valueOf(books.observedAt().map(Instant::toEpochMilli).orElse(0L)),
                PATH_RULE_VERSION,
                CombatPowerSimulationService.RULE_VERSION,
                String.valueOf(specUp.costRuleVersions()),
                specUp.armGuardEnabled() ? "ag" : "-")
            + polishSignature(polish)
            + karmaSignature()
            + (pick == Pick.KRAKEN ? ":k" + SpecUpKrakenScore.RULE_VERSION : "")
            + (krakenUnavailable == null ? "" : ":ku" + krakenUnavailable);
    var cached = store.findPath(key);
    if (cached.isPresent()) {
      pathOutcome("cache_hit");
      return cached.get().withSupportOrderAvailable(supportOrderAvailable, krakenScoreRole);
    }
    var computed =
        single(
                "path:" + key,
                () -> {
                  var holder = client.get();
                  var refusal = takeSeat(holder);
                  if (refusal != null) {
                    pathOutcome(refusal.outcome);
                    log.warn(
                        "event=spec_up_path_busy reason={} client={} pick={} limit={} slots={}",
                        refusal.outcome,
                        com.loakraken.common.lostark.ClientLimiter.shortKey(holder),
                        pick,
                        limit,
                        SOLVE_MAX_CONCURRENT);
                    throw new BusinessException(SpecUpErrorCode.SPEC_UP_BUSY, PATH_RETRY_AFTER);
                  }
                  try {
                    long started = nanoTime.getAsLong();
                    var result =
                        computePath(
                            snapshot,
                            before,
                            pick,
                            limit,
                            gems,
                            refinePrices,
                            polish,
                            krakenContext,
                            krakenUnavailable);
                    long elapsed = nanoTime.getAsLong() - started;
                    pathOutcome("computed");
                    Timer.builder("loakraken.spec_up.path_compute")
                        .tag("pick", pick.name())
                        .tag("stop", result.stopReasonCode())
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meters)
                        .record(Duration.ofNanos(elapsed));
                    if (TIME_LIMITED.equals(result.stopReasonCode()))
                      log.warn(
                          "event=spec_up_path_time_limit pick={} limit={} steps={} elapsedMs={}",
                          pick,
                          limit,
                          result.steps().size(),
                          elapsed / 1_000_000);
                    else store.savePath(key, result, CACHE_TTL);
                    return result;
                  } finally {
                    releaseSeat(holder);
                  }
                })
            .withSupportOrderAvailable(supportOrderAvailable, krakenScoreRole);
    return computed;
  }

  /** KRAKEN 요청을 실제 기준으로: 쓸 수 있으면 KRAKEN과 점수 문맥, 아니면 EFFICIENCY와 그 이유. */
  private record KrakenPick(Pick pick, SpecUpKrakenScore.Context context, String unavailable) {}

  private KrakenPick resolveKraken(CharacterResult snapshot, Pick requested) {
    if (requested != Pick.KRAKEN) return new KrakenPick(requested, null, null);
    if (!specUp.supportOrderEnabled()) return new KrakenPick(Pick.EFFICIENCY, null, "DISABLED");
    var context = specUp.krakenContext(snapshot);
    if (!context.available()) return new KrakenPick(Pick.EFFICIENCY, null, context.unavailable());
    return new KrakenPick(Pick.KRAKEN, context, null);
  }

  private Path computePath(
      CharacterResult snapshot,
      BigDecimal before,
      Pick pick,
      int limit,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices,
      List<SpecUpService.PolishStep> polish,
      SpecUpKrakenScore.Context krakenContext,
      String krakenUnavailable) {
    var krakenBefore = krakenContext == null ? null : krakenContext.shown();
    var profile = snapshot.details().profile();
    var roleReason = CombatPowerSimulationService.roleReason(snapshot);
    // 서포터 KRAKEN(PM 총괄 11-15 (d) (a)): 서포터 전투력 모델이 없어 점수만으로 고르고 단계 전투력은 null이다. 그 밖(서포터
    // EFFICIENCY·DELTA
    // 포함)은 지금과 같다.
    boolean scoreOnly = roleReason != null && krakenContext != null && krakenContext.support();
    if (!scoreOnly && (roleReason != null || before == null)) {
      var reason =
          roleReason != null
              ? CombatPowerReasons.classify(roleReason)
              : CombatPowerReasons.NO_OFFICIAL_COMBAT_POWER;
      return new Path(
          profile.characterName(),
          profile.serverName(),
          profile.className(),
          before,
          before,
          pick.name(),
          limit,
          reason.code(),
          reason.text(),
          null,
          PATH_RULE_VERSION,
          CombatPowerSimulationService.RULE_VERSION,
          snapshot.fetchedAt(),
          null,
          List.of(),
          List.of(),
          krakenBefore,
          krakenBefore,
          krakenUnavailable);
    }
    // 추천 순서는 완갑(팔 보호구) 재련도 고른다(목록 완갑 스위치를 따름). 목표 경로는 그대로(완갑 없음).
    boolean armGuard = specUp.armGuardEnabled();
    var chain =
        chain(
            snapshot,
            before,
            null,
            pick,
            // KRAKEN·EFFICIENCY(PM 총괄 11-19 (B)): 비용을 모르는 단계도 고른다(비율이 없어 비용 있는 단계 뒤, 비용은 모름).
            // DELTA는 그대로.
            pick == Pick.DELTA,
            limit,
            armGuard,
            gems,
            refinePrices,
            polish,
            krakenContext,
            scoreOnly);
    // 점수만 고르는 서포터 경로는 전투력이 오르는 것을 잴 수 없어 unpriced를 비운다. KRAKEN·EFFICIENCY는 비용을 모르는 단계도 고르므로(PM 총괄
    // 11-19 (B)) '비용을 몰라 빠진 후보'가 없다(DELTA만 남는다).
    var unpriced =
        scoreOnly || pick != Pick.DELTA
            ? List.<Excluded>of()
            : unpricedAtStart(snapshot, before, armGuard, gems, refinePrices, polish);
    return new Path(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        before,
        scoreOnly ? null : chain.last(),
        pick.name(),
        limit,
        chain.stop(),
        // KRAKEN은 점수가 오르는 단계를 고르므로 그 문장(코드 리뷰 #967 P3).
        pick == Pick.KRAKEN && PATH_STOP_TEXT.containsKey(chain.stop() + ":KRAKEN")
            ? PATH_STOP_TEXT.get(chain.stop() + ":KRAKEN")
            : PATH_STOP_TEXT.get(chain.stop()),
        chain.cumulative(),
        PATH_RULE_VERSION,
        CombatPowerSimulationService.RULE_VERSION,
        snapshot.fetchedAt(),
        chain.pricesAt(),
        chain.steps(),
        unpriced,
        krakenBefore,
        chain.krakenLast() == null ? krakenBefore : SpecUpKrakenScore.round2(chain.krakenLast()),
        krakenUnavailable);
  }

  /** 지금 상태에서 비용을 몰라 순서에서 뺀 다음 단계 중 전투력이 오르는 것. Δ 큰 순, 같으면 id. */
  private List<Excluded> unpricedAtStart(
      CharacterResult snapshot,
      BigDecimal before,
      boolean armGuard,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices,
      List<SpecUpService.PolishStep> polish) {
    var pricesAt = new Instant[1];
    var result = new ArrayList<Excluded>();
    for (var option :
        options(
            State.of(snapshot.details().loadout(), armGuard, true)
                .withStoneOrientations(specUp.stoneOrientations(snapshot))
                .withKarma(specUp.karmaPathLevel(snapshot)),
            gems,
            refinePrices,
            pricesAt,
            polish,
            false)) {
      if (option.cost() != null && option.cost().amount() != null) continue;
      // '상·상 뒤' 후보는 처음 상태에서 고를 수 없다.
      if (afterTop(polish, option.id())) continue;
      var simulated = simulator.simulate(snapshot, option.changes());
      if (simulated.unsupportedCount() > 0 || simulated.after() == null) continue;
      var delta = simulated.after().subtract(before);
      if (delta.signum() <= 0) continue;
      result.add(
          new Excluded(
              option.id(),
              option.kind(),
              option.target(),
              option.from(),
              option.to(),
              option.icon(),
              option.grade(),
              option.skillName(),
              delta,
              option.cost()));
    }
    result.sort(
        Comparator.comparing(Excluded::deltaCombatPower, Comparator.reverseOrder())
            .thenComparing(Excluded::candidateId));
    return List.copyOf(result);
  }

  /** 경로의 한 단계. 표시 필드는 스펙업 후보와 같은 뜻이고 change는 시뮬레이터 요청 changes[] 하나와 같다. */
  public record Step(
      int order,
      String candidateId,
      String kind,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      Change change,
      BigDecimal deltaCombatPower,
      BigDecimal combatPowerAfter,
      SpecUpCost cost,
      Long cumulativeCost,
      BigDecimal combatPowerPer10kGold,
      Long extraCost,
      List<Change> changes,
      BigDecimal deltaKrakenScore,
      BigDecimal krakenScoreAfter,
      BigDecimal krakenScorePer100kGold) {
    /** 점수 필드 없는 단계(KRAKEN이 아닌 순서·목표 경로). */
    Step(
        int order,
        String candidateId,
        String kind,
        String target,
        String from,
        String to,
        String icon,
        String grade,
        String skillName,
        Change change,
        BigDecimal deltaCombatPower,
        BigDecimal combatPowerAfter,
        SpecUpCost cost,
        Long cumulativeCost,
        BigDecimal combatPowerPer10kGold,
        Long extraCost,
        List<Change> changes) {
      this(
          order,
          candidateId,
          kind,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          deltaCombatPower,
          combatPowerAfter,
          cost,
          cumulativeCost,
          combatPowerPer10kGold,
          extraCost,
          changes,
          null,
          null,
          null);
    }
  }

  public record Goal(
      String characterName,
      String serverName,
      String className,
      BigDecimal combatPower,
      BigDecimal targetCombatPower,
      BigDecimal finalCombatPower,
      boolean reached,
      String stopReasonCode,
      String stopReason,
      Long totalCost,
      int unpricedSteps,
      String ruleVersion,
      String simulatorRuleVersion,
      Instant fetchedAt,
      Instant pricesAt,
      List<Step> steps) {}

  public Goal goal(String characterName, BigDecimal target) {
    if (!specUp.enabled()) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    var snapshot = simulator.snapshot(characterName);
    var profile = snapshot.details().profile();
    var before =
        profile.combatPower() == null
            ? null
            : profile.combatPower().setScale(2, RoundingMode.HALF_UP);
    var goal = target.setScale(2, RoundingMode.HALF_UP);
    if (before != null && goal.compareTo(before.multiply(BigDecimal.TWO)) > 0)
      throw new BusinessException(SpecUpErrorCode.SPEC_UP_GOAL_OUT_OF_RANGE);
    var gems = gemPrices();
    var refinePrices = materials.cached();
    var polish = specUp.polishSteps(snapshot);
    var key =
        String.join(
                ":",
                profile.serverName(),
                profile.characterName(),
                goal.toPlainString(),
                String.valueOf(
                    snapshot.fetchedAt() == null ? 0 : snapshot.fetchedAt().toEpochMilli()),
                String.valueOf(latest(gems)),
                // 이전 시세(stale)로 바뀐 칸은 관측 시각이 그대로라, 어느 칸이 이전 시세인지도 키에 넣는다(코드 리뷰 10-25 P1: 지금 시세로 만든
                // 경로를
                // 이전 시세가 된 뒤에도 stale=false로 주지 않게).
                staleSignature(gems),
                String.valueOf(refinePrices.map(p -> p.observedAt().toEpochMilli()).orElse(0L)),
                // 각인서 시세가 바뀌면 각인 비용이 달라지므로 다시 계산한다(시세가 그대로면 캐시 그대로).
                String.valueOf(books.observedAt().map(Instant::toEpochMilli).orElse(0L)),
                RULE_VERSION,
                CombatPowerSimulationService.RULE_VERSION,
                String.valueOf(specUp.costRuleVersions()),
                specUp.armGuardEnabled() ? "ag" : "-")
            + polishSignature(polish)
            + karmaSignature();
    var cached = store.find(key);
    if (cached.isPresent()) {
      goalOutcome("cache_hit");
      return cached.get();
    }
    return single(
        key,
        () -> {
          var holder = client.get();
          var refusal = takeSeat(holder);
          if (refusal != null) {
            goalOutcome(refusal.outcome);
            log.warn(
                "event=spec_up_goal_busy reason={} client={} slots={}",
                refusal.outcome,
                com.loakraken.common.lostark.ClientLimiter.shortKey(holder),
                SOLVE_MAX_CONCURRENT);
            throw new BusinessException(SpecUpErrorCode.SPEC_UP_BUSY, PATH_RETRY_AFTER);
          }
          try {
            // 계산 시간 측정은 시간 상한 판정에 쓰는 시계(nanoTime)와 따로 잰다(상한 판정을 건드리지 않게).
            long started = System.nanoTime();
            var result = compute(snapshot, before, goal, gems, refinePrices, polish);
            goalOutcome("computed");
            // 추천 순서처럼 계산 시간(p95·p99)을 남긴다. stop은 멈춘 이유 코드(작은 고정 집합).
            Timer.builder("loakraken.spec_up.goal_compute")
                .tag("stop", result.stopReasonCode())
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters)
                .record(Duration.ofNanos(System.nanoTime() - started));
            // 시간 상한으로 멈춘 부분 결과는 캐시하지 않는다(PM-프론트 L31 ③).
            if (!TIME_LIMITED.equals(result.stopReasonCode())) store.save(key, result, CACHE_TTL);
            return result;
          } finally {
            releaseSeat(holder);
          }
        });
  }

  private Goal compute(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices,
      List<SpecUpService.PolishStep> polish) {
    var roleReason = CombatPowerSimulationService.roleReason(snapshot);
    if (roleReason != null || before == null) {
      var reason =
          roleReason != null
              ? CombatPowerReasons.classify(roleReason)
              : CombatPowerReasons.NO_OFFICIAL_COMBAT_POWER;
      return result(
          snapshot, before, target, before, reason.code(), reason.text(), null, 0, null, List.of());
    }
    if (before.compareTo(target) >= 0)
      return result(
          snapshot, before, target, before, ALREADY_REACHED, null, 0L, 0, null, List.of());
    var chain =
        chain(
            snapshot,
            before,
            target,
            Pick.EFFICIENCY,
            false,
            MAX_STEPS,
            specUp.armGuardEnabled(),
            gems,
            refinePrices,
            polish,
            null,
            false);
    return result(
        snapshot,
        before,
        target,
        chain.last(),
        chain.stop(),
        null,
        chain.cumulative(),
        chain.unpriced(),
        chain.pricesAt(),
        chain.steps());
  }

  /**
   * 다음 단계를 고르는 기준. EFFICIENCY: 더 드는 비용 1만 골드당 전투력(목표 경로와 같음). DELTA: 전투력 증가가 큰 순(같으면 효율·id).
   * KRAKEN: 크라켄 점수(서포터는 서포터 점수식)가 오르는 단계만, 더 드는 비용이 0 이하인 단계가 먼저(Δ점수 큰 순), 그다음 10만 골드당 Δ점수 큰 순(같으면
   * Δ점수·id). spec-up.support-order.enabled가 꺼졌거나 점수를 낼 수 없으면 EFFICIENCY로 되돌린다.
   */
  public enum Pick {
    EFFICIENCY,
    DELTA,
    KRAKEN
  }

  /** 한 번 이어 고른 결과. last = 마지막 단계 뒤 전투력(단계가 없으면 처음 전투력). */
  record Chain(
      List<Step> steps,
      String stop,
      Long cumulative,
      int unpriced,
      BigDecimal last,
      Instant pricesAt,
      Double krakenLast) {}

  /**
   * 지금 상태에서 다음 한 단계를 고르고 적용한 뒤 다시 모든 칸의 다음 단계를 시뮬레이터로 재기를 되풀이한다(사슬). target이 있으면 닿을 때 멈추고,
   * pricedOnly면 비용(더 드는 비용)을 모르는 단계는 고르지 않는다. 같은 입력이면 같은 사슬이다(같으면 id 순).
   */
  private Chain chain(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      Pick pick,
      boolean pricedOnly,
      int maxSteps,
      boolean armGuard,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices,
      List<SpecUpService.PolishStep> polish,
      SpecUpKrakenScore.Context kraken,
      boolean scoreOnly) {
    var pricesAt = new Instant[1];
    var steps = new ArrayList<Step>();
    // KRAKEN(spec-up.support-order.enabled): 단계마다 크라켄 점수로 고른다. 점수는 처음 입력에 칸마다 마지막 단계 변경(연마는 점수 입력
    // 편집)을
    // 적용해 다시 잰다. 작열(쿨) 보석 칸은 목록 KRAKEN 기본처럼 뺀다(PM 총괄 11-15 (d) C).
    var loadout = snapshot.details().loadout();
    var krakenExcluded =
        kraken == null ? java.util.Set.<String>of() : SpecUpService.cooldownGemSlots(loadout);
    var appliedEdits = new HashMap<String, SpecUpKrakenScore.Edit>();
    Double scoreNow = kraken == null ? null : kraken.before();
    // 추천 순서와 목표 경로 모두 어빌리티 스톤 목표 조합도 고른다(목표 경로 v3).
    var state =
        State.of(snapshot.details().loadout(), armGuard, true)
            .withStoneOrientations(specUp.stoneOrientations(snapshot))
            .withKarma(specUp.karmaPathLevel(snapshot));
    // 칸마다 그 칸의 마지막 단계 변경(스톤은 각인 둘이라 여러 개).
    var applied = new LinkedHashMap<String, List<Change>>();
    var current = before;
    Long cumulative = 0L;
    int unpriced = 0;
    // 경로 합계: 보석 칸·어빌리티 스톤은 그 칸의 마지막 단계 비용(칸마다 하나, 스톤은 새로 깎은 마지막 스톤만), 그 밖은 단계 비용의 합. 모르는 값이 있으면
    // 합계도 모름(path v4·goal v4, PM 총괄 11-09).
    var gemTotals = new HashMap<String, Long>();
    // 추천 순서 단계 비용(paidCost): 칸마다 앞 단계에서 산 품목의 가격(보석·연마, 모르면 null).
    var held = new HashMap<String, Long>();
    // 동점 가르기(D)의 각인 순서: 캐릭터 각인 목록 순서(계산 검증 11-19 P3, 비교 기준과 같음).
    var engravingOrder = new HashMap<String, Integer>();
    if (loadout.engravings() != null && loadout.engravings().arkPassiveEffects() != null) {
      int index = 0;
      for (var engraving : loadout.engravings().arkPassiveEffects())
        if (engraving != null && engraving.name() != null)
          engravingOrder.putIfAbsent(engraving.name(), index++);
    }
    // 연마: 후보 id → 단계(줄마다 목표 변경), 칸마다 마지막으로 고른 연마 후보 id.
    var polishById = new HashMap<String, SpecUpService.PolishStep>();
    for (var step : polish) polishById.put(step.id(), step);
    var polishChosen = new HashMap<String, String>();
    long others = 0;
    boolean othersUnknown = false;
    // 추천 순서 KRAKEN·EFFICIENCY(PM 총괄 11-19, 비교 기준과 같음, 추천 순서의 10-18 '공짜 먼저' 대체):
    // 고르기는 비율 있는 단계 먼저(공짜·비용 모름은 뒤), 합계는 단계마다 낸 비용(paidCost)의 합.
    // 모르는 비용이 하나라도 있으면 합계도 모름.
    // DELTA와 목표 경로는 그대로(목표 경로는 자기 규칙: '가장 싸게 목표까지'라 공짜 먼저, 비교 기준에 없는 기능).
    boolean baseline = target == null && pick != Pick.DELTA;
    long paidSum = 0;
    boolean paidUnknown = false;
    long started = nanoTime.getAsLong();
    String stop;
    while (true) {
      if (target != null && current.compareTo(target) >= 0) {
        stop = REACHED;
        break;
      }
      if (steps.size() >= maxSteps) {
        stop = STEP_LIMIT;
        break;
      }
      if (nanoTime.getAsLong() - started > TIME_LIMIT.toNanos()) {
        stop = TIME_LIMITED;
        break;
      }
      Option best = null;
      Long bestMarginal = null;
      Double bestScore = null;
      // 레벨 점프는 추천 순서 KRAKEN·EFFICIENCY만(비교 기준 경로와 같음). DELTA·목표 경로는 다음 한 단계.
      for (var listed : options(state, gems, refinePrices, pricesAt, polish, baseline)) {
        var option = listed;
        if (krakenExcluded.contains(option.slot())) continue;
        // 99·109·1010돌은 목록에만(EFFICIENCY·KRAKEN 정렬). 추천 순서(세 기준)와 목표 경로는 뺀다.
        // 닿는 경로마다 셋을 모두 골라 합계가 약 341억이 되고, 아무도 사지 않는 1010돌이 합계를 덮는다.
        // (PM 총괄 11-19, 비교 기준 경로와 다른 점)
        if (SpecUpScoreCandidates.extremeStone(option.id())) continue;
        var polishStep = polishById.get(option.id());
        // '상·상 뒤' 셋째 줄 후보는 이 장신구의 지금 매물이 상·상(HH)일 때만(비교 기준은 지금 상태에서 후보를 만든다).
        if (polishStep != null
            && polishStep.afterTop()
            && !"HH".equals(polishCode(polishChosen.get(option.slot())))) continue;
        if (polishStep != null && applied.containsKey(option.slot())) {
          // 같은 장신구를 다시 바꾸는 단계: 앞 단계보다 등급을 낮추는 매물은 고르지 않고(비교 기준과 같음), 앞 단계가 바꾼 줄을 이 단계가 바꾸지 않으면 그 줄을
          // 처음
          // 값으로 되돌리는 변경을 더한다(단계 changes를 이어 다시 재도 같은 값, 계산 검증 11-15 #951 HOLD).
          if (lowersPolish(option.id(), polishChosen.get(option.slot()))) continue;
          option = withPolishResets(option, polishStep, applied.get(option.slot()));
          if (option == null) continue;
        }
        // 고르는 효율의 비용(PM 총괄 11-19): 추천 순서 KRAKEN·EFFICIENCY는 비교 기준처럼 단계마다 낸 비용
        // (스톤·귀속은 전부, 거래 가능은 앞 단계 품목과의 차이). DELTA와 목표 경로는 더 드는 비용(보석은 누적 차이) 그대로.
        var marginal = baseline ? paidCost(option, held) : marginal(option, gemTotals);
        if (pricedOnly && marginal == null) continue;
        var trial = new LinkedHashMap<>(applied);
        trial.put(option.slot(), option.changes());
        BigDecimal delta = null;
        BigDecimal after = null;
        if (!scoreOnly) {
          var simulated = simulator.simulate(snapshot, flatten(trial));
          // 지금까지 적용한 단계는 모두 계산된 것이므로, 계산하지 못한 변경이 있으면 이 후보다.
          if (simulated.unsupportedCount() > 0 || simulated.after() == null) continue;
          after = simulated.after();
          delta = after.subtract(current);
        }
        Double score = null;
        if (kraken != null) {
          // KRAKEN은 점수가 오르는 단계만(전투력은 계산된 값을 그대로 보인다).
          var trialEdits = new HashMap<>(appliedEdits);
          if (polishStep != null && polishStep.kraken() != null)
            trialEdits.put(option.slot(), polishStep.kraken());
          else trialEdits.remove(option.slot());
          score = specUp.chainedKrakenScore(kraken, loadout, trial, trialEdits);
          if (score == null || score - scoreNow <= 0) continue;
        } else if (delta.signum() <= 0) continue;
        // 고르기는 반올림하지 않은 비율로(소수 둘째 자리에서 같아져 id 순으로 갈리지 않게, 계산 검증 11-01). 화면 값은 단계를 만들 때 따로 반올림한다.
        var scored = option.scored(delta, after, delta == null ? null : rank(delta, marginal));
        if (best == null
            || (kraken != null
                ? krakenPreferred(
                    score - scoreNow,
                    marginal,
                    scored.id(),
                    bestScore - scoreNow,
                    bestMarginal,
                    best.id(),
                    engravingOrder)
                : pick == Pick.DELTA
                    ? BY_DELTA.compare(scored, best) < 0
                    : baseline
                        ? baselinePreferred(scored, marginal, best, bestMarginal, engravingOrder)
                        : preferred(scored, free(marginal), best, free(bestMarginal)))) {
          best = scored;
          bestMarginal = marginal;
          bestScore = score;
        }
      }
      if (best == null) {
        stop = NO_CANDIDATE;
        break;
      }
      applied.put(best.slot(), best.changes());
      if (polishById.containsKey(best.id())) polishChosen.put(best.slot(), best.id());
      BigDecimal deltaScore = null;
      BigDecimal scoreAfter = null;
      BigDecimal scorePer100k = null;
      if (kraken != null) {
        var chosen = polishById.get(best.id());
        if (chosen != null && chosen.kraken() != null)
          appliedEdits.put(best.slot(), chosen.kraken());
        else appliedEdits.remove(best.slot());
        double raw = bestScore - scoreNow;
        // 목록 deltaKrakenScore와 같은 표시: 반올림 전 차를 소수 둘째 자리로.
        deltaScore = SpecUpKrakenScore.round2(raw);
        scoreAfter = SpecUpKrakenScore.round2(bestScore);
        if (bestMarginal != null && bestMarginal > 0)
          scorePer100k = SpecUpKrakenScore.roundEfficiency(raw * 100_000d / bestMarginal);
        scoreNow = bestScore;
      }
      state = state.after(best);
      if (!scoreOnly) current = best.after();
      var amount = best.cost() == null ? null : best.cost().amount();
      if (amount == null) unpriced++;
      if (slotCost(best)) {
        gemTotals.put(best.slot(), amount);
        held.put(best.slot(), best.cost() == null ? null : best.cost().itemPrice());
      } else if (amount == null) othersUnknown = true;
      else others += amount;
      if (bestMarginal == null) paidUnknown = true;
      else paidSum += bestMarginal;
      cumulative =
          baseline ? (paidUnknown ? null : paidSum) : total(others, othersUnknown, gemTotals);
      steps.add(
          new Step(
              steps.size() + 1,
              best.id(),
              best.kind(),
              best.target(),
              best.from(),
              best.to(),
              best.icon(),
              best.grade(),
              best.skillName(),
              best.change(),
              best.delta(),
              best.after(),
              best.cost(),
              cumulative,
              best.delta() == null ? null : SpecUpCost.efficiency(best.delta(), bestMarginal),
              bestMarginal,
              best.changes(),
              deltaScore,
              scoreAfter,
              scorePer100k));
    }
    return new Chain(
        List.copyOf(steps), stop, cumulative, unpriced, current, pricesAt[0], scoreNow);
  }

  /**
   * KRAKEN 고르기(PM 총괄 11-19, 비교 기준의 고르기 규칙과 같음): (1) 비율(Δ점수 ÷ 더 드는 비용)이 있는 단계가 먼저, 비율 큰 순. 더 드는 비용이
   * 0 이하이거나 모르면 비율이 없어 비용 있는 단계 뒤(공짜 단계를 먼저 두지 않음). (2) 같으면 Δ점수 큰 순, (3) 그다음 더 드는 비용 낮은 순(모르는 비용은
   * 가장 큼), (4) 후보 순서(candidateOrder, 비교 기준과 같음).
   */
  static boolean krakenPreferred(
      double delta, Long marginal, String id, double bestDelta, Long bestMarginal, String bestId) {
    return krakenPreferred(delta, marginal, id, bestDelta, bestMarginal, bestId, Map.of());
  }

  static boolean krakenPreferred(
      double delta,
      Long marginal,
      String id,
      double bestDelta,
      Long bestMarginal,
      String bestId,
      Map<String, Integer> engravingOrder) {
    boolean rated = rated(marginal);
    boolean bestRated = rated(bestMarginal);
    if (rated != bestRated) return rated;
    if (rated) {
      double rate = delta / marginal;
      double bestRate = bestDelta / bestMarginal;
      if (rate != bestRate) return rate > bestRate;
    }
    if (delta != bestDelta) return delta > bestDelta;
    if (!java.util.Objects.equals(marginal, bestMarginal)) {
      if (marginal == null) return false;
      if (bestMarginal == null) return true;
      return marginal < bestMarginal;
    }
    return candidateOrder(id, bestId, engravingOrder) < 0;
  }

  /**
   * EFFICIENCY 추천 순서 고르기(PM 총괄 11-19, 10-18 '공짜 먼저' 대체, 비교 기준과 같음): krakenPreferred와 같은 규칙을 전투력 Δ로.
   * 비율(효율)이 있는 단계 먼저(효율 큰 순), Δ 큰 순, 단계 비용 낮은 순(모름은 가장 큼), 후보 순서(candidateOrder).
   */
  static boolean baselinePreferred(
      Option candidate, Long marginal, Option best, Long bestMarginal) {
    return baselinePreferred(candidate, marginal, best, bestMarginal, Map.of());
  }

  static boolean baselinePreferred(
      Option candidate,
      Long marginal,
      Option best,
      Long bestMarginal,
      Map<String, Integer> engravingOrder) {
    boolean rated = rated(marginal);
    boolean bestRated = rated(bestMarginal);
    if (rated != bestRated) return rated;
    if (rated) {
      int byRate = candidate.efficiency().compareTo(best.efficiency());
      if (byRate != 0) return byRate > 0;
    }
    int byDelta = candidate.delta().compareTo(best.delta());
    if (byDelta != 0) return byDelta > 0;
    if (!java.util.Objects.equals(marginal, bestMarginal)) {
      if (marginal == null) return false;
      if (bestMarginal == null) return true;
      return marginal < bestMarginal;
    }
    return candidateOrder(candidate.id(), best.id(), engravingOrder) < 0;
  }

  /**
   * 같은 비율·Δ·비용일 때의 후보 종류 순서(PM 총괄 11-19 (D), 비교 기준의 후보 순서): 보석 → 재련·상급 재련·품질 → 장신구(연마) → 스톤 → 각인.
   */
  static final List<String> CANDIDATE_KINDS =
      List.of("GEM_LEVEL", "REFINE", "POLISH", "ABILITY_STONE", "ENGRAVING_LEVEL", "KARMA_LEVEL");

  /** 재련 묶음(비교 기준은 재련·상급 재련·품질을 한 종류로 칸 번호 순, 코드 리뷰 #993 P3). 같은 칸이면 이 순서. */
  static final List<String> REFINE_KINDS =
      List.of("EQUIPMENT_REFINE", "EQUIPMENT_ADVANCED_REFINE", "EQUIPMENT_QUALITY");

  /**
   * 마지막 동점 가르기(PM 총괄 11-19 (D)): id 문자열 대신 비교 기준의 후보 순서. 종류 순(CANDIDATE_KINDS, 모르는 종류는 뒤), 같은 종류는 칸
   * 번호를 숫자로(POLISH:9가 POLISH:10보다 먼저), 스톤은 목표 97 → 106 → 107 순, 그래도 같으면 id. 음수면 a가 먼저.
   */
  static int candidateOrder(String a, String b) {
    return candidateOrder(a, b, Map.of());
  }

  /** engravingOrder: 각인 이름 → 캐릭터 각인 목록 순서. 같은 비율·Δ·비용의 각인 단계는 이 순서로(없는 이름은 뒤, 계산 검증 11-19 P3). */
  static int candidateOrder(String a, String b, Map<String, Integer> engravingOrder) {
    var pa = a.split(":");
    var pb = b.split(":");
    int ka = kindRank(pa[0]);
    int kb = kindRank(pb[0]);
    if (ka != kb) return Integer.compare(ka, kb);
    if ("ENGRAVING_LEVEL".equals(pa[0]) && pa.length > 1 && pb.length > 1) {
      int byOrder =
          Integer.compare(
              engravingOrder.getOrDefault(pa[1], Integer.MAX_VALUE),
              engravingOrder.getOrDefault(pb[1], Integer.MAX_VALUE));
      if (byOrder != 0) return byOrder;
    } else if ("ABILITY_STONE".equals(pa[0])) {
      int byTarget = Integer.compare(stoneTargetRank(pa), stoneTargetRank(pb));
      if (byTarget != 0) return byTarget;
    } else if (pa.length > 1 && pb.length > 1) {
      var sa = slotNumber(pa[1]);
      var sb = slotNumber(pb[1]);
      if (sa != null && sb != null && !sa.equals(sb)) return Integer.compare(sa, sb);
      if (REFINE_KINDS.contains(pa[0]) && !pa[0].equals(pb[0]))
        return Integer.compare(REFINE_KINDS.indexOf(pa[0]), REFINE_KINDS.indexOf(pb[0]));
    }
    return a.compareTo(b);
  }

  private static int kindRank(String kind) {
    int rank = CANDIDATE_KINDS.indexOf(REFINE_KINDS.contains(kind) ? "REFINE" : kind);
    return rank < 0 ? CANDIDATE_KINDS.size() : rank;
  }

  private static Integer slotNumber(String token) {
    try {
      return Integer.valueOf(token);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  /**
   * 스톤 id(ABILITY_STONE:각인1 레벨:각인2 레벨)의 목표 순서(SpecUpScoreCandidates.STONE_TARGETS, 97 → 106 → 107 →
   * 99 → 109 → 1010). 모르면 맨 뒤.
   */
  private static int stoneTargetRank(String[] parts) {
    if (parts.length < 3) return Integer.MAX_VALUE;
    var x = slotNumber(parts[1]);
    var y = slotNumber(parts[2]);
    if (x == null || y == null) return Integer.MAX_VALUE;
    var targets = SpecUpScoreCandidates.STONE_TARGETS;
    for (int k = 0; k < targets.size(); k++) {
      int lo = Math.min(targets.get(k)[2], targets.get(k)[3]);
      int hi = Math.max(targets.get(k)[2], targets.get(k)[3]);
      if (Math.min(x, y) == lo && Math.max(x, y) == hi) return k;
    }
    return Integer.MAX_VALUE;
  }

  /** 비율(Δ ÷ 더 드는 비용)을 낼 수 있는 비용인가: 알고 0보다 크다. */
  static boolean rated(Long marginal) {
    return marginal != null && marginal > 0;
  }

  /**
   * 연마 후보 id 끝의 등급 코드(주옵션 둘: N 없음·L 하·M 중·H 상, 셋째 줄 고정값 후보는 A 공격력·W 무기 공격력 + 등급)를 앞 단계와 견준다. 주옵션
   * 가운데 하나라도 낮추면 true. 앞 단계에 셋째 줄 고정값이 있으면 비교 기준처럼 그보다 높은 등급의 고정값(공격력·무기 공격력 어느 쪽이든)만 다음 후보다: 고정값을
   * 버리거나 같거나 낮은 등급이면 true(코드 리뷰 #951 P3: 옵션이 다른 고정값도 등급으로 견줌).
   */
  static boolean lowersPolish(String id, String chosen) {
    if (chosen == null) return false;
    var next = polishCode(id);
    var before = polishCode(chosen);
    for (int k = 0; k < 2; k++)
      if (polishRank(next.charAt(k)) < polishRank(before.charAt(k))) return true;
    if (before.length() == 4)
      return next.length() < 4 || polishRank(next.charAt(3)) <= polishRank(before.charAt(3));
    return false;
  }

  /** 연마 후보 id 끝의 등급 코드(없으면 null). */
  static String polishCode(String id) {
    return id == null ? null : id.substring(id.lastIndexOf(':') + 1);
  }

  /** 이 후보가 '상·상 뒤' 셋째 줄 후보인지. */
  private static boolean afterTop(List<SpecUpService.PolishStep> polish, String id) {
    for (var step : polish) if (step.id().equals(id)) return step.afterTop();
    return false;
  }

  private static int polishRank(char grade) {
    return "NLMH".indexOf(grade);
  }

  /** 앞 단계 변경 가운데 이 단계가 바꾸지 않는 줄을 이 단계의 목표 값(처음 장신구 값)으로 되돌리는 변경을 더한 후보. 목표 값이 없으면 null. */
  private static Option withPolishResets(
      Option option, SpecUpService.PolishStep step, List<Change> prior) {
    var changes = new ArrayList<>(option.changes());
    var lines = new java.util.HashSet<Integer>();
    for (var change : changes) lines.add(change.line());
    for (var change : prior) {
      if (change.kind() != Kind.POLISH || lines.contains(change.line())) continue;
      var reset = step.lineChange(change.line());
      if (reset == null) return null;
      changes.add(reset);
      lines.add(change.line());
    }
    return new Option(
        option.slot(),
        option.id(),
        option.target(),
        option.from(),
        option.to(),
        option.icon(),
        option.grade(),
        option.skillName(),
        changes.getFirst(),
        option.cost(),
        option.delta(),
        option.after(),
        option.efficiency(),
        List.copyOf(changes.subList(1, changes.size())));
  }

  private static List<Change> flatten(Map<String, List<Change>> applied) {
    var changes = new ArrayList<Change>();
    for (var list : applied.values()) changes.addAll(list);
    return List.copyOf(changes);
  }

  /** DELTA 고르기: 전투력 증가 큰 순 → 효율 높은 순(없으면 뒤) → id. */
  static final Comparator<Option> BY_DELTA =
      Comparator.comparing(Option::delta, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::efficiency, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::id);

  private static Goal result(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      BigDecimal last,
      String code,
      String text,
      Long totalCost,
      int unpriced,
      Instant pricesAt,
      List<Step> steps) {
    var profile = snapshot.details().profile();
    return new Goal(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        before,
        target,
        last,
        last != null && last.compareTo(target) >= 0,
        code,
        text != null ? text : STOP_TEXT.get(code),
        totalCost,
        unpriced,
        RULE_VERSION,
        CombatPowerSimulationService.RULE_VERSION,
        snapshot.fetchedAt(),
        pricesAt,
        List.copyOf(steps));
  }

  /** 단계마다 그 비용 전부를 내는 비용 코드: 귀속(또는 빈 칸) 보석, 거래할 수 없는 장신구(새로 사서 바꾼다, 비교 기준과 같음). */
  static final java.util.Set<String> FULL_EACH_STEP =
      java.util.Set.of("GEM_BUY_BOUND", "GEM_BUY", "POLISH_BUY");

  /**
   * 추천 순서(KRAKEN·EFFICIENCY)의 단계 비용(PM 총괄 11-19, 비교 기준과 같음): 어빌리티 스톤은 앞 스톤과 상관없이 그 스톤 비용 전부(C), 귀속
   * 보석·거래할 수 없는 장신구는 단계마다 새 가격 전부, 거래할 수 있는 보석·장신구는 새 가격 − 앞 단계 가격(0 미만은 0). 그 밖은 단계 비용. 모르면 null.
   * 고르는 비율과 경로 합계(단계 비용의 합)에 함께 쓴다.
   */
  static Long paidCost(Option option, Map<String, Long> held) {
    var amount = option.cost() == null ? null : option.cost().amount();
    if (amount == null) return null;
    if ("ABILITY_STONE".equals(option.kind()) || !slotCost(option)) return amount;
    if (FULL_EACH_STEP.contains(option.cost().code())) return amount;
    // 거래 가능: 그 칸의 첫 단계는 처음 품목과의 차이(목록 비용 그대로), 이어 바꾸는 단계는 새 품목 가격 − 앞 단계에서 산 품목 가격(0 미만은 0).
    if (!held.containsKey(option.slot())) return amount;
    var previous = held.get(option.slot());
    var price = option.cost().itemPrice();
    return previous == null || price == null ? null : Math.max(0, price - previous);
  }

  /**
   * DELTA·목표 경로의 이 단계에 더 드는 비용: 보석은 지금 누적 − 그 칸에서 이미 낸 누적(처음 단계는 0), 어빌리티 스톤은 이 스톤 비용 − 앞 스톤 단계
   * 비용(처음은 0), 그 밖은 단계 비용. 모르면 null.
   */
  static Long marginal(Option option, Map<String, Long> gemTotals) {
    var amount = option.cost() == null ? null : option.cost().amount();
    if (amount == null || !slotCost(option)) return amount;
    var paid =
        gemTotals.containsKey(option.slot()) ? gemTotals.get(option.slot()) : Long.valueOf(0);
    return paid == null ? null : amount - paid;
  }

  /**
   * 칸마다 마지막 단계 비용만 합계에 세는 단계: 보석 레벨(누적 비용), 어빌리티 스톤(새로 깎는 스톤, PM 총괄 11-09), 연마(같은 장신구 칸은 새 매물로 바꾸므로
   * 마지막 매물 비용만).
   */
  static boolean slotCost(Option option) {
    return option.change().kind() == Kind.GEM_LEVEL
        || "ABILITY_STONE".equals(option.kind())
        || "POLISH".equals(option.kind());
  }

  /**
   * 연마 단계 후보가 캐시 키 끝에 붙이는 값: 스위치가 꺼졌거나 후보가 없으면 ""(키가 그대로라 배포 뒤 캐시가 한꺼번에 비지 않음, 코드 리뷰 #951 P2), 있으면
   * ":pp" + id·비용·관측 시각의 해시(연마 시세가 바뀌면 다시 계산).
   */
  static String polishSignature(List<SpecUpService.PolishStep> polish) {
    if (polish.isEmpty()) return "";
    var parts = new ArrayList<String>();
    for (var step : polish)
      parts.add(
          step.id()
              + "="
              + (step.cost() == null ? "" : step.cost().amount())
              + "@"
              + (step.cost() == null || step.cost().priceObservedAt() == null
                  ? ""
                  : step.cost().priceObservedAt().toEpochMilli()));
    return ":pp" + Integer.toHexString(String.join(",", parts).hashCode());
  }

  /**
   * 더 드는 비용이 0 이하로 확인된 단계(거꾸로 시세의 보석 단계 등). 효율은 '-'(null)이지만 목표 경로에는 먼저 넣는다(추천 순서에서는 비율 없는 단계라 뒤,
   * PM 총괄 11-19).
   */
  static boolean free(Long marginal) {
    return marginal != null && marginal <= 0;
  }

  /**
   * 다음 단계 고르기: 목표 경로는 '가장 싸게 목표까지 가는 계획'이라 더 드는 비용이 0 이하인 단계를 맨 앞에(Δ 큰 순) 넣어 단계 상한에 밀려 빠지지 않게 하고, 그
   * 밖은 ORDER(효율 높은 순, 효율 없음은 뒤, 같으면 Δ·id). 목록 정렬(효율 없는 줄은 뒤)과는 다르다(PM 판정 10-18).
   */
  static boolean preferred(Option candidate, boolean candidateFree, Option best, boolean bestFree) {
    if (candidateFree != bestFree) return candidateFree;
    if (candidateFree) {
      int byDelta = best.delta().compareTo(candidate.delta());
      return byDelta != 0 ? byDelta < 0 : candidate.id().compareTo(best.id()) < 0;
    }
    return ORDER.compare(candidate, best) < 0;
  }

  /** 경로 합계(보석 칸마다 마지막 누적 + 그 밖 합). 모르는 값이 있으면 null. */
  static Long total(long others, boolean othersUnknown, Map<String, Long> gemTotals) {
    if (othersUnknown || gemTotals.containsValue(null)) return null;
    long sum = others;
    for (var value : gemTotals.values()) sum += value;
    return sum;
  }

  /**
   * 고르는 효율: Δ × 10,000 ÷ 더 드는 비용(반올림 없음, 소수 12자리). 더 드는 비용이 0 이하이거나 모르면 null. 화면 값은
   * SpecUpCost.efficiency.
   */
  static BigDecimal rank(BigDecimal delta, Long marginal) {
    return SpecUpCost.rankEfficiency(delta, marginal);
  }

  /** 효율 있음(내림차순) → 비용 모름(Δ 내림차순) → id 순. 스펙업 가이드의 효율 순과 같다. */
  static final Comparator<Option> ORDER =
      Comparator.comparing((Option option) -> option.efficiency() == null ? 1 : 0)
          .thenComparing(Option::efficiency, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::delta, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::id);

  /**
   * 한 칸의 목표 단계들. jumps면 다음 단계부터 끝(max)까지 하나하나가 후보(레벨 점프, 비교 기준과 같음: 계산 검증 11-20 22:21), 아니면 다음 한
   * 단계만.
   */
  static List<Integer> targets(int current, int max, boolean jumps) {
    if (current >= max) return List.of();
    if (!jumps) return List.of(current + 1);
    var result = new ArrayList<Integer>();
    for (int target = current + 1; target <= max; target++) result.add(target);
    return result;
  }

  /** 상급 재련 목표: jumps면 10의 배수만(다음 10단계 … 40, +1 후보 없음, 비교 기준과 같음), 아니면 다음 한 단계. */
  static List<Integer> advancedTargets(int current, boolean jumps) {
    if (current >= 40) return List.of();
    if (!jumps) return List.of(current + 1);
    var result = new ArrayList<Integer>();
    for (int target = (current / 10 + 1) * 10; target <= 40; target += 10) result.add(target);
    return result;
  }

  /**
   * 여러 단계를 한 번에 올리는 비용 = 단계 비용의 합(기대 비용은 더해진다, 계산 검증 11-20 22:21). 한 단계면 그 비용 그대로. 모르는 단계가 하나라도 있으면
   * 그 단계의 비용(모름)을 준다. 시세 관측 시각은 가장 이른 것, 이전 시세(stale)는 하나라도 있으면 true.
   */
  static SpecUpCost rangeCost(List<SpecUpCost> stages) {
    if (stages.size() == 1) return stages.getFirst();
    long amount = 0;
    double exact = 0;
    Instant observedAt = null;
    Boolean stale = null;
    for (var stage : stages) {
      if (stage == null || stage.amount() == null) return stage;
      amount += stage.amount();
      exact += stage.exact() == null ? stage.amount() : stage.exact();
      if (stage.priceObservedAt() != null
          && (observedAt == null || stage.priceObservedAt().isBefore(observedAt)))
        observedAt = stage.priceObservedAt();
      if (stage.stale() != null) stale = Boolean.TRUE.equals(stale) || stage.stale();
    }
    var first = stages.getFirst();
    return new SpecUpCost(
        first.unit(),
        amount,
        first.status(),
        first.code(),
        first.basis(),
        exact,
        observedAt,
        stale,
        null);
  }

  /**
   * 지금 상태에서 칸마다 다음 단계들. jumps(추천 순서 KRAKEN·EFFICIENCY)면 보석·재련·완갑·각인은 끝까지의 모든 레벨, 상급 재련은 10의 배수가
   * 후보다(레벨 점프). DELTA·목표 경로는 다음 한 단계(PM 총괄 판단 전 기본).
   */
  private List<Option> options(
      State state,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices,
      Instant[] pricesAt,
      List<SpecUpService.PolishStep> polish,
      boolean jumps) {
    var result = new ArrayList<Option>();
    // 연마 단계(spec-up.polish-path.enabled): 목록 연마 교체 줄과 같은 목표·id·비용. 같은 장신구 칸의 다른 매물을 고르면 앞 매물을
    // 바꾼다(칸마다 마지막
    // 변경만, 다시 잴 때도 같음).
    for (var step : polish) {
      if (step.changes().isEmpty()) continue;
      if (step.cost() != null && step.cost().priceObservedAt() != null)
        pricesAt[0] =
            pricesAt[0] == null || step.cost().priceObservedAt().isBefore(pricesAt[0])
                ? step.cost().priceObservedAt()
                : pricesAt[0];
      result.add(
          new Option(
              step.slot(),
              step.id(),
              step.target(),
              step.from(),
              step.to(),
              step.icon(),
              step.grade(),
              null,
              step.changes().getFirst(),
              step.cost(),
              null,
              null,
              null,
              step.changes().subList(1, step.changes().size())));
    }
    for (var gem : state.gems().values())
      for (int next : targets(gem.level(), 10, jumps)) {
        String label = gem.level() + "레벨 " + (gem.kind() == null ? "보석" : gem.kind());
        String target =
            gem.skill() == null ? "보석 " + gem.slot() + "번 · " + label : gem.skill() + " · " + label;
        result.add(
            new Option(
                "GEM_LEVEL:" + gem.slot(),
                "GEM_LEVEL:" + gem.slot() + ":" + next,
                target,
                gem.level() + "레벨",
                next + "레벨",
                gem.icon(),
                gem.grade(),
                gem.skill(),
                new Change(
                    Kind.GEM_LEVEL,
                    gem.slot(),
                    null,
                    null,
                    next,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                gemCost(gems, pricesAt, gem, next),
                null,
                null,
                null));
      }
    for (var item : state.refines().values()) {
      // 단계 비용은 칸마다 한 번만 재고(재련 계획), 점프는 그 합.
      var stages = new ArrayList<SpecUpCost>();
      for (int next : targets(item.refine(), 25, jumps)) {
        int from = next - 1;
        stages.add(
            from >= 10 && from < 25
                ? specUp.refineCost(item.equipment(), from, refinePrices, pricesAt)
                : SpecUpCost.REFINE_COST_UNCONFIRMED);
        result.add(
            new Option(
                "EQUIPMENT_REFINE:" + item.index(),
                "EQUIPMENT_REFINE:" + item.index() + ":" + next,
                item.equipment().name(),
                "+" + item.refine(),
                "+" + next,
                item.equipment().icon(),
                item.equipment().grade(),
                null,
                new Change(
                    Kind.EQUIPMENT_REFINE,
                    null,
                    item.index(),
                    null,
                    null,
                    next,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                rangeCost(stages),
                null,
                null,
                null));
      }
    }
    if (state.armGuard() != null) {
      var item = state.armGuard();
      var stages = new ArrayList<SpecUpCost>();
      for (int next : targets(item.refine(), 25, jumps)) {
        stages.add(specUp.armGuardStepCost(next - 1, refinePrices, pricesAt));
        // 등급은 목록 완갑 줄과 같이 처음 등급에서 목표 단계로 정한다(시뮬레이터 ARM_GUARD는 목표 단계·등급을 그대로 받는다).
        var grade = specUp.armGuardGrade(item.equipment().grade(), next);
        if (grade != null)
          result.add(
              new Option(
                  "EQUIPMENT_REFINE:" + item.index(),
                  "EQUIPMENT_REFINE:" + item.index() + ":" + next,
                  item.equipment().name(),
                  "+" + item.refine(),
                  "+" + next + (grade.equals(item.equipment().grade()) ? "" : " " + grade),
                  item.equipment().icon(),
                  item.equipment().grade(),
                  null,
                  new Change(
                      Kind.ARM_GUARD,
                      null,
                      null,
                      null,
                      next,
                      null,
                      null,
                      null,
                      grade,
                      null,
                      null,
                      null,
                      null,
                      null),
                  rangeCost(stages),
                  null,
                  null,
                  null));
      }
    }
    for (var item : state.advanced().values())
      for (int next : advancedTargets(item.refine(), jumps)) {
        result.add(
            new Option(
                "EQUIPMENT_ADVANCED_REFINE:" + item.index(),
                "EQUIPMENT_ADVANCED_REFINE:" + item.index() + ":" + next,
                item.equipment().name(),
                "상급 재련 " + item.refine() + "단계",
                "상급 재련 " + next + "단계",
                item.equipment().icon(),
                item.equipment().grade(),
                null,
                new Change(
                    Kind.EQUIPMENT_ADVANCED_REFINE,
                    null,
                    item.index(),
                    null,
                    next,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                SpecUpCost.REFINE_COST_UNCONFIRMED,
                null,
                null,
                null));
      }
    if (state.quality() != null) {
      var weapon = state.quality();
      int next = Math.min(100, (weapon.refine() / 10 + 1) * 10);
      result.add(
          new Option(
              "EQUIPMENT_QUALITY:" + weapon.index(),
              "EQUIPMENT_QUALITY:" + weapon.index() + ":" + next,
              weapon.equipment().name(),
              "품질 " + weapon.refine(),
              "품질 " + next,
              weapon.equipment().icon(),
              weapon.equipment().grade(),
              null,
              new Change(
                  Kind.EQUIPMENT_QUALITY,
                  null,
                  weapon.index(),
                  null,
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              SpecUpCost.QUALITY_COST_UNCONFIRMED,
              null,
              null,
              null));
    }
    for (var engraving : state.engravings().values()) {
      // 한 레벨 비용(각인서 5권)을 올리는 레벨 수만큼 더한다(비교 기준: 각인서 × (목표 − 지금) × 5).
      var perLevel =
          SpecUpService.bookCost(engraving.name(), engraving.grade(), books::price, pricesAt);
      var stages = new ArrayList<SpecUpCost>();
      for (int next :
          targets(engraving.level(), SpecUpService.engravingMaxLevel(engraving.grade()), jumps)) {
        stages.add(perLevel);
        result.add(
            new Option(
                "ENGRAVING_LEVEL:" + engraving.name(),
                "ENGRAVING_LEVEL:" + engraving.name() + ":" + next,
                engraving.name() + " 각인",
                engraving.level() + "레벨",
                next + "레벨",
                // 추천 순서 줄도 스펙업 후보와 같은 유물 각인서 공식 아이콘(시세 캐시, 공식 호출 없음).
                books.icon(engraving.name()).orElse(null),
                engraving.grade(),
                null,
                new Change(
                    Kind.ENGRAVING_LEVEL,
                    null,
                    null,
                    null,
                    next,
                    null,
                    null,
                    null,
                    null,
                    engraving.name(),
                    null,
                    null,
                    null,
                    null),
                rangeCost(stages),
                null,
                null,
                null));
      }
    }
    if (state.stone() != null) result.addAll(stoneOptions(state.stone()));
    if (state.karma() != null) result.addAll(karmaOptions(state.karma()));
    return result;
  }

  /** 카르마 단계 스위치가 켜지면 캐시 키 끝에 ":kc"(꺼져 있으면 키가 그대로라 배포 뒤 캐시가 한꺼번에 비지 않음). */
  private String karmaSignature() {
    return specUp.karmaCpEnabled() ? ":kc" : "";
  }

  /** 깨달음 카르마 최대 레벨(SpecUpScoreCandidates와 같음). */
  static final int KARMA_MAX = 30;

  /**
   * 깨달음 카르마 단계(목록 카르마 줄과 같은 id·대상·비용): 지금 레벨 → 다음 레벨 … 30 가운데 하나(비교 기준처럼 한 번에 여러 레벨도 고른다). 비용은 지금
   * 레벨에서 그 레벨까지의 기대 골드(SpecUpCostRules.karmaGold, 거래 불가라 단계마다 그대로 낸다).
   */
  static List<Option> karmaOptions(int current) {
    var result = new ArrayList<Option>();
    for (int next = current + 1; next <= KARMA_MAX; next++) {
      var gold = com.loakraken.specup.domain.SpecUpCostRules.karmaGold(current, next);
      result.add(
          new Option(
              "KARMA_LEVEL",
              "KARMA_LEVEL:" + next,
              "깨달음 카르마",
              current + "레벨",
              next + "레벨",
              null,
              null,
              null,
              CombatPowerSimulationService.karmaLevelChange(next),
              gold == null ? SpecUpCost.KARMA_COST_UNCONFIRMED : SpecUpCost.karma(gold),
              null,
              null,
              null));
    }
    return result;
  }

  /**
   * 어빌리티 스톤 목표 조합(목록 스톤 줄과 같은 목표·id·비용): 조합마다 목록과 같은 배치 하나(어느 각인에 높은 레벨). 한 단계에 각인
   * 둘(STONE_ENGRAVING 둘)을 바꾼다. 비용은 새 스톤 기대 비용 전부(스톤은 이어 올리지 않고 새로 깎는다). 지금과 같은 레벨 조합은 뺀다.
   */
  static List<Option> stoneOptions(Stone stone) {
    var names = List.copyOf(stone.levels().keySet());
    var result = new ArrayList<Option>();
    var seen = new java.util.HashSet<String>();
    for (var target : SpecUpScoreCandidates.STONE_TARGETS) {
      var label = target[0] + "" + target[1] + "돌";
      var gold = com.loakraken.specup.domain.SpecUpCostRules.stoneGold(label);
      var cost = gold == null ? SpecUpCost.STONE_COST_UNCONFIRMED : SpecUpCost.stone(gold);
      // 목록과 같은 배치 하나만(PM 총괄 11-15: 뒤집은 배치는 같은 점수에 다른 전투력이라 비교 기준에 없는 숫자가 된다). 목록 배치를 모르면 앞 배치.
      var first = new LinkedHashMap<String, Integer>();
      first.put(names.get(0), target[2]);
      first.put(names.get(1), target[3]);
      var flipped = new LinkedHashMap<String, Integer>();
      flipped.put(names.get(0), target[3]);
      flipped.put(names.get(1), target[2]);
      // 목록 배치가 있으면(점수를 읽을 수 있음) 그 배치만, 목록에 없는 조합(점수가 오르지 않음)은 뺀다. 점수 입력을 읽을 수 없으면 앞 배치.
      Map<String, Integer> chosen;
      if (!orientationsMatch(stone)) chosen = first;
      else if (stone.orientations().contains(first)) chosen = first;
      else if (stone.orientations().contains(flipped)) chosen = flipped;
      else continue;
      for (var order : List.of(new int[] {chosen.get(names.get(0)), chosen.get(names.get(1))})) {
        var levels = new LinkedHashMap<String, Integer>();
        levels.put(names.get(0), order[0]);
        levels.put(names.get(1), order[1]);
        if (levels.equals(stone.levels())) continue;
        var id = "ABILITY_STONE:" + order[0] + ":" + order[1];
        if (!seen.add(id)) continue;
        result.add(
            new Option(
                "ABILITY_STONE",
                id,
                stone.item().name(),
                stoneText(stone.levels()),
                label + " " + stoneText(levels),
                stone.item().icon(),
                stone.item().grade(),
                null,
                stoneChange(names.get(0), order[0]),
                cost,
                null,
                null,
                null,
                List.of(stoneChange(names.get(1), order[1]))));
      }
    }
    return result;
  }

  /**
   * 목록 배치를 이 스톤에 쓸 수 있는지: 배치가 있고(빈 목록 = 점수가 오르는 조합 없음도 씀) 각인 이름이 이 스톤과 같다. 점수 입력과 장비 툴팁의 각인 이름이
   * 갈라지면 모든 스톤 단계를 조용히 빼지 않고 앞 배치로 둔다(코드 리뷰 #975 P3).
   */
  static boolean orientationsMatch(Stone stone) {
    if (stone.orientations() == null) return false;
    if (stone.orientations().isEmpty()) return true;
    var names = stone.levels().keySet();
    for (var orientation : stone.orientations())
      if (orientation.keySet().equals(names)) return true;
    return false;
  }

  /** 어빌리티 스톤의 감소 줄 이름(각인이 아니라 스톤 단계에서 바꾸지 않는다). */
  static final java.util.Set<String> STONE_PENALTIES =
      java.util.Set.of("공격력 감소", "방어력 감소", "공격속도 감소", "이동속도 감소");

  static Change stoneChange(String name, int level) {
    return new Change(
        Kind.STONE_ENGRAVING,
        null,
        null,
        null,
        level,
        null,
        null,
        null,
        null,
        name,
        null,
        null,
        null,
        null);
  }

  private static String stoneText(Map<String, Integer> levels) {
    var parts = new ArrayList<String>();
    levels.forEach((name, level) -> parts.add(name + " Lv." + level));
    return String.join(" · ", parts);
  }

  /**
   * 보석 단계 비용 = 지금에서 그 레벨까지의 누적 비용(목록 직접 비용과 같은 식: max(그 레벨 최저가 − 지금 보석 최저가, 0), 귀속·빈 칸이면 그 레벨 최저가).
   * 단계마다 따로 구한다 — 그 레벨 시세가 없으면 그 단계만 모름, 지금 레벨 시세가 없으면 모든 단계가 모름(PM 판정 10-18 15:18·15:22).
   */
  static SpecUpCost gemCost(
      Map<String, MarketAuctionService.GemCell> gems, Instant[] pricesAt, GemSlot gem, int next) {
    return SpecUpService.gemCost(
        gems, pricesAt, gem.priceKind(), gem.original(), next, gem.bound());
  }

  private Map<String, MarketAuctionService.GemCell> gemPrices() {
    var prices = new HashMap<String, MarketAuctionService.GemCell>();
    for (var cell : auctions.cachedGems()) prices.put(cell.kind() + ":" + cell.level(), cell);
    return prices;
  }

  private static long latest(Map<String, MarketAuctionService.GemCell> gems) {
    long latest = 0;
    for (var cell : gems.values())
      if (cell.cell() != null && cell.cell().observedAt() != null)
        latest = Math.max(latest, cell.cell().observedAt().toEpochMilli());
    return latest;
  }

  /** 이전 시세 칸 수와 어느 칸인지(정렬한 칸 이름의 해시). 이전 시세가 없으면 "0". */
  static String staleSignature(Map<String, MarketAuctionService.GemCell> gems) {
    var stale = new java.util.TreeSet<String>();
    for (var entry : gems.entrySet())
      if (entry.getValue().stale() && entry.getValue().cell() != null) stale.add(entry.getKey());
    return stale.isEmpty()
        ? "0"
        : stale.size() + "-" + Integer.toHexString(String.join(",", stale).hashCode());
  }

  @SuppressWarnings("unchecked")
  private <T> T single(String key, java.util.function.Supplier<T> loader) {
    var future = new CompletableFuture<Object>();
    var existing = flights.putIfAbsent(key, future);
    if (existing != null) {
      try {
        return (T) existing.join();
      } catch (CompletionException exception) {
        if (exception.getCause() instanceof RuntimeException cause) throw cause;
        throw exception;
      }
    }
    try {
      var result = loader.get();
      future.complete(result);
      return result;
    } catch (RuntimeException | Error exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      flights.remove(key, future);
    }
  }

  /** 다음 한 단계 후보. delta·after·efficiency는 시뮬레이터로 잰 뒤 채운다. */
  record Option(
      String slot,
      String id,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      Change change,
      SpecUpCost cost,
      BigDecimal delta,
      BigDecimal after,
      BigDecimal efficiency,
      List<Change> more) {
    /** 변경이 하나인 단계. */
    Option(
        String slot,
        String id,
        String target,
        String from,
        String to,
        String icon,
        String grade,
        String skillName,
        Change change,
        SpecUpCost cost,
        BigDecimal delta,
        BigDecimal after,
        BigDecimal efficiency) {
      this(
          slot,
          id,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          cost,
          delta,
          after,
          efficiency,
          List.of());
    }

    Option scored(BigDecimal delta, BigDecimal after, BigDecimal efficiency) {
      return new Option(
          slot,
          id,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          cost,
          delta,
          after,
          efficiency,
          more);
    }

    /** 이 단계의 시뮬레이터 변경 전부(change가 먼저). */
    List<Change> changes() {
      if (more == null || more.isEmpty()) return List.of(change);
      var all = new ArrayList<Change>();
      all.add(change);
      all.addAll(more);
      return List.copyOf(all);
    }

    /** 단계 종류: 변경이 하나면 그 kind, 여럿이면(어빌리티 스톤) 후보 id의 종류(목록과 같음). */
    String kind() {
      // 카르마 단계는 시뮬레이터 변경(ARK_PASSIVE_POINT 깨달음 level)과 달리 목록과 같은 종류 KARMA_LEVEL.
      if (id != null && id.startsWith("KARMA_LEVEL:")) return "KARMA_LEVEL";
      if (more == null || more.isEmpty()) return change.kind().name();
      int colon = id.indexOf(':');
      return colon < 0 ? id : id.substring(0, colon);
    }
  }

  /** 어빌리티 스톤: 각인 이름 → 레벨(스톤 줄 순서), 표시용 장비. */
  record Stone(
      CharacterLoadout.Equipment item,
      Map<String, Integer> levels,
      List<Map<String, Integer>> orientations) {
    /** orientations = 목록 스톤 줄과 같은 배치(목표 조합마다 하나, null이면 앞 배치). */
    Stone(CharacterLoadout.Equipment item, Map<String, Integer> levels) {
      this(item, levels, null);
    }
  }

  record GemSlot(
      int slot,
      String kind,
      String priceKind,
      int original,
      int level,
      boolean bound,
      String skill,
      String icon,
      String grade) {}

  /** refine은 재련 단계(상급 재련이면 상급 단계, 품질이면 품질 값)다. */
  record Item(int index, CharacterLoadout.Equipment equipment, int refine) {}

  record Engraving(String name, String grade, int level) {}

  /** 지금까지 적용한 단계를 반영한 칸별 상태. 다음 단계가 없는 칸은 빠진다. */
  record State(
      Map<Integer, GemSlot> gems,
      Map<Integer, Item> refines,
      Map<Integer, Item> advanced,
      Item quality,
      Map<String, Engraving> engravings,
      Item armGuard,
      Stone stone,
      Integer karma) {
    /** 스톤 배치를 목록과 같게(목표 조합마다 하나). 스톤이 없거나 배치를 모르면 그대로. */
    State withStoneOrientations(List<Map<String, Integer>> orientations) {
      if (stone == null || orientations == null) return this;
      return new State(
          gems,
          refines,
          advanced,
          quality,
          engravings,
          armGuard,
          new Stone(stone.item(), stone.levels(), orientations),
          karma);
    }

    /**
     * 깨달음 카르마 단계(spec-up.karma-cp.enabled): 지금 레벨에서 다음 레벨 … 30까지 한 단계에 하나(목록 카르마 줄과 같은 id·비용).
     * null이거나 30이면 넣지 않는다.
     */
    State withKarma(Integer level) {
      var next = level == null || level >= KARMA_MAX ? null : level;
      return new State(gems, refines, advanced, quality, engravings, armGuard, stone, next);
    }

    /** 완갑(팔 보호구)·어빌리티 스톤은 넣지 않는 상태(목표 경로). */
    static State of(CharacterLoadout loadout) {
      return of(loadout, false, false);
    }

    /**
     * armGuard면 완갑 재련 단계도 다음 단계 후보로 둔다(+0~24, 목록 완갑 줄과 같은 대상). stone이면 어빌리티 스톤 목표 조합도 둔다(각인 줄이 둘인
     * 스톤만).
     */
    static State of(CharacterLoadout loadout, boolean armGuard, boolean stone) {
      var gems = new LinkedHashMap<Integer, GemSlot>();
      if (loadout.gems() != null) {
        var skills = new HashMap<Integer, String>();
        var effects = SpecUpService.gemEffects(loadout);
        if (loadout.gems().effects() != null)
          for (var skill : loadout.gems().effects().skills())
            if (skill != null && skill.gemSlot() != null && skill.name() != null)
              skills.putIfAbsent(skill.gemSlot(), skill.name());
        for (var gem : loadout.gems().items()) {
          if (gem == null || gem.slot() == null || gem.level() == null || gem.level() >= 10)
            continue;
          var matcher = gem.name() == null ? null : SpecUpService.GEM.matcher(gem.name());
          String kind = matcher != null && matcher.find() ? matcher.group(2) : null;
          gems.put(
              gem.slot(),
              new GemSlot(
                  gem.slot(),
                  kind,
                  SpecUpService.priceKind(kind, effects.get(gem.slot())),
                  gem.level(),
                  gem.level(),
                  SpecUpService.bound(gem),
                  skills.get(gem.slot()),
                  gem.icon(),
                  gem.grade()));
        }
      }
      var refines = new LinkedHashMap<Integer, Item>();
      var advanced = new LinkedHashMap<Integer, Item>();
      Item quality = null;
      for (int index = 0; index < loadout.equipment().size(); index++) {
        var item = loadout.equipment().get(index);
        if (item == null || item.detail() == null) continue;
        if (SpecUpService.WEAPON_ARMOR.contains(item.type())) {
          var refine = item.detail().refineLevel();
          if (refine != null && refine >= 10 && refine < 25)
            refines.put(index, new Item(index, item, refine));
          var level = item.detail().advancedRefineLevel();
          // 전율 장비에는 상급 재련이 없다(E21).
          if (level != null
              && level < 40
              && SpecUpService.refineGrade(item.name()) != RefiningRules.Grade.JEONYUL)
            advanced.put(index, new Item(index, item, level));
        }
        if (quality == null
            && "무기".equals(item.type())
            && item.detail().quality() != null
            && item.detail().quality() < 100)
          quality = new Item(index, item, item.detail().quality());
      }
      var engravings = new LinkedHashMap<String, Engraving>();
      if (loadout.engravings() != null)
        for (var engraving : loadout.engravings().arkPassiveEffects())
          if (engraving != null
              && engraving.name() != null
              && engraving.level() != null
              && engraving.level() < SpecUpService.engravingMaxLevel(engraving.grade()))
            engravings.put(
                engraving.name(),
                new Engraving(engraving.name(), engraving.grade(), engraving.level()));
      Item guard = null;
      if (armGuard) {
        var item = SpecUpKrakenScore.armGuardItem(loadout);
        if (item != null && item.detail() != null && item.detail().refineLevel() != null) {
          int refine = item.detail().refineLevel();
          if (refine >= 0 && refine < 25)
            guard = new Item(loadout.equipment().indexOf(item), item, refine);
        }
      }
      Stone stoneState = null;
      if (stone) {
        var item =
            loadout.equipment().stream()
                .filter(
                    equipment ->
                        equipment != null
                            && "어빌리티 스톤".equals(equipment.type())
                            && equipment.detail() != null)
                .findFirst()
                .orElse(null);
        if (item != null) {
          var levels = new LinkedHashMap<String, Integer>();
          // 실제 스톤에는 감소 줄(예: 방어력 감소 Lv.0)이 셋째로 붙는다. 각인 줄만 센다(계산 검증 11-01 P1: 감소 줄 때문에 스톤 단계가 하나도 나오지
          // 않음).
          for (var line : item.detail().stoneEngravings())
            if (line != null
                && line.name() != null
                && line.level() != null
                && !STONE_PENALTIES.contains(line.name()))
              levels.putIfAbsent(line.name(), line.level());
          if (levels.size() == 2) stoneState = new Stone(item, levels);
        }
      }
      return new State(gems, refines, advanced, quality, engravings, guard, stoneState, null);
    }

    /** option 단계를 적용한 다음 상태. 끝 단계에 닿은 칸은 뺀다. */
    State after(Option option) {
      var gems = new LinkedHashMap<>(this.gems);
      var refines = new LinkedHashMap<>(this.refines);
      var advanced = new LinkedHashMap<>(this.advanced);
      var quality = this.quality;
      var engravings = new LinkedHashMap<>(this.engravings);
      var armGuard = this.armGuard;
      var stone = this.stone;
      var karma = this.karma;
      var change = option.change();
      switch (change.kind()) {
        case GEM_LEVEL -> {
          var gem = gems.get(change.slot());
          if (change.level() >= 10) gems.remove(change.slot());
          else
            gems.put(
                change.slot(),
                new GemSlot(
                    gem.slot(),
                    gem.kind(),
                    gem.priceKind(),
                    gem.original(),
                    change.level(),
                    gem.bound(),
                    gem.skill(),
                    gem.icon(),
                    gem.grade()));
        }
        case EQUIPMENT_REFINE -> {
          var item = refines.get(change.index());
          if (change.refine() >= 25) refines.remove(change.index());
          else
            refines.put(change.index(), new Item(item.index(), item.equipment(), change.refine()));
        }
        case EQUIPMENT_ADVANCED_REFINE -> {
          var item = advanced.get(change.index());
          if (change.level() >= 40) advanced.remove(change.index());
          else
            advanced.put(change.index(), new Item(item.index(), item.equipment(), change.level()));
        }
        case EQUIPMENT_QUALITY ->
            quality =
                change.quality() >= 100
                    ? null
                    : new Item(quality.index(), quality.equipment(), change.quality());
        case ENGRAVING_LEVEL -> {
          var engraving = engravings.get(change.name());
          if (change.level() >= SpecUpService.engravingMaxLevel(engraving.grade()))
            engravings.remove(change.name());
          else
            engravings.put(
                change.name(), new Engraving(engraving.name(), engraving.grade(), change.level()));
        }
        case ARM_GUARD ->
            armGuard =
                change.level() >= 25
                    ? null
                    : new Item(armGuard.index(), armGuard.equipment(), change.level());
        case STONE_ENGRAVING -> {
          var levels = new LinkedHashMap<>(stone.levels());
          for (var each : option.changes()) levels.put(each.name(), each.level());
          stone = new Stone(stone.item(), levels, stone.orientations());
        }
        case ARK_PASSIVE_POINT -> {
          if (CombatPowerSimulationService.karmaChange(change))
            karma = change.karmaLevel() >= KARMA_MAX ? null : change.karmaLevel();
        }
        default -> {}
      }
      return new State(gems, refines, advanced, quality, engravings, armGuard, stone, karma);
    }
  }
}
