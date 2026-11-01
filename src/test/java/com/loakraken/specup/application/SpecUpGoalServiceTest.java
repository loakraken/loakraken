package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.market.application.RelicBookPriceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/** 스펙업 목표 경로(L31)의 상한: 30단계에서 멈추고, 계산 시간 상한으로 멈춘 결과는 캐시하지 않는다. */
class SpecUpGoalServiceTest {
  private final CombatPowerSimulationService simulator = mock(CombatPowerSimulationService.class);
  private final MarketAuctionService auctions = mock(MarketAuctionService.class);
  private final RefiningMaterialPriceService materials = mock(RefiningMaterialPriceService.class);
  private final SpecUpService specUp = mock(SpecUpService.class);
  private final MemoryStore store = new MemoryStore();

  /** 테스트 캐릭터 장비(기본 없음). service() 전에 바꾼다. */
  private List<CharacterLoadout.Equipment> equipment = List.of();

  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

  @Test
  void 단계가_30개를_넘으면_STEP_LIMIT으로_멈추고_시세가_없으면_누적_비용은_null이다() {
    var goal = service(System::nanoTime).goal("목표테스트", new BigDecimal("6000"));
    assertThat(goal.stopReasonCode()).isEqualTo("STEP_LIMIT");
    assertThat(goal.steps()).hasSize(30);
    assertThat(goal.reached()).isFalse();
    assertThat(goal.totalCost()).isNull();
    assertThat(goal.unpricedSteps()).isEqualTo(30);
    // 단계마다 전투력이 1씩 늘고(가짜 시뮬레이터), 마지막 단계 값이 경로 끝 값이다.
    assertThat(goal.finalCombatPower()).isEqualByComparingTo("3030");
    assertThat(goal.steps().getLast().combatPowerAfter()).isEqualByComparingTo("3030");
    assertThat(store.saved).hasSize(1);
  }

  @Test
  void 계산_시간_상한으로_멈춘_부분_결과는_캐시하지_않는다() {
    var calls = new AtomicInteger();
    LongSupplier clock = () -> calls.getAndIncrement() < 2 ? 0L : Duration.ofSeconds(3).toNanos();
    var goal = service(clock).goal("목표테스트", new BigDecimal("6000"));
    assertThat(goal.stopReasonCode()).isEqualTo("TIME_LIMIT");
    assertThat(goal.stopReason()).isEqualTo("계산 시간이 길어 여기까지 고른 단계만 보여 드려요.");
    assertThat(goal.steps()).hasSize(1);
    assertThat(store.saved).isEmpty();
  }

  @Test
  void 각인서_시세_시각이_바뀌면_캐시_키가_달라져_다시_계산하고_그대로면_캐시를_쓴다() {
    var service = service(System::nanoTime);
    when(books.observedAt()).thenReturn(Optional.of(Instant.parse("2026-10-17T09:00:00Z")));
    service.goal("목표테스트", new BigDecimal("6000"));
    service.goal("목표테스트", new BigDecimal("6000"));
    assertThat(store.saved).hasSize(1);

    when(books.observedAt()).thenReturn(Optional.of(Instant.parse("2026-10-17T09:01:00Z")));
    service.goal("목표테스트", new BigDecimal("6000"));
    assertThat(store.saved).hasSize(2);
  }

  /**
   * 지금 시세로 만든 경로가 캐시에 있어도, 같은 칸이 이전 시세(마지막 관측 값, 관측 시각 그대로)가 되면 다시 계산해 stale=true로 준다(코드 리뷰 10-25
   * P1). 다시 지금 시세가 되면 그 키의 캐시를 다시 쓴다.
   */
  @Test
  void 보석_칸이_이전_시세가_되면_캐시를_쓰지_않고_stale로_다시_계산한다() {
    var service = service(System::nanoTime);
    var at = Instant.parse("2026-10-25T12:00:00Z");
    when(auctions.cachedGems()).thenReturn(gemCells(at, false));
    var fresh = service.goal("목표테스트", new BigDecimal("3002"));
    assertThat(store.saved).hasSize(1);
    assertThat(fresh.steps()).isNotEmpty();
    assertThat(fresh.steps().getFirst().cost().stale()).isFalse();

    // 3분 칸이 만료돼 같은 관측 값이 이전 시세로 보인다(관측 시각은 그대로).
    when(auctions.cachedGems()).thenReturn(gemCells(at, true));
    var stale = service.goal("목표테스트", new BigDecimal("3002"));
    assertThat(store.saved).hasSize(2);
    assertThat(stale.steps().getFirst().cost().stale()).isTrue();
    assertThat(stale.steps().getFirst().cost().priceObservedAt()).isEqualTo(at);

    // 다시 지금 시세면 처음 키의 캐시를 쓴다.
    when(auctions.cachedGems()).thenReturn(gemCells(at, false));
    assertThat(service.goal("목표테스트", new BigDecimal("3002"))).isEqualTo(fresh);
    assertThat(store.saved).hasSize(2);
  }

  /**
   * 추천 순서(목표 없음): 시세가 있는 단계만 limit까지 이어 고르고, 각 단계 전투력은 그때까지 고른 change 전체를 시뮬레이터에 넣은 값이다(이어 잰 Δ의 합 =
   * 처음부터 다시 잰 값). 같은 입력이면 같은 순서다.
   */
  @Test
  void 추천_순서는_시세가_있는_단계만_limit까지_이어_고르고_다시_재도_같다() {
    var service = service(System::nanoTime);
    when(auctions.cachedGems()).thenReturn(gemCells(Instant.parse("2026-10-31T12:00:00Z"), false));
    var path = service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 5);
    assertThat(path.steps()).hasSize(5);
    assertThat(path.stopReasonCode()).isEqualTo("STEP_LIMIT");
    assertThat(path.unpriced()).isEmpty();
    // 같은 칸(candidateId에서 마지막 ':목표' 앞)은 마지막 단계의 change만 남긴다(서비스가 그 칸을 바꿔 끼우는 것과 같음).
    var changes = new java.util.LinkedHashMap<String, CombatPowerSimulationService.Change>();
    BigDecimal deltas = BigDecimal.ZERO;
    Long previous = 0L;
    for (var step : path.steps()) {
      changes.put(
          step.candidateId().substring(0, step.candidateId().lastIndexOf(':')), step.change());
      deltas = deltas.add(step.deltaCombatPower());
      assertThat(step.cost().amount()).isNotNull();
      assertThat(step.cumulativeCost()).isGreaterThanOrEqualTo(previous);
      // 이 단계에 더 드는 비용 = 경로 합계가 늘어난 만큼(보석은 같은 칸 누적 차이)
      assertThat(step.extraCost()).isEqualTo(step.cumulativeCost() - previous);
      previous = step.cumulativeCost();
      // 그 단계까지의 change를 처음부터 시뮬레이터에 넣은 값 = 그 단계 전투력
      var fresh =
          simulator.simulate(simulator.snapshot("목표테스트"), List.copyOf(changes.values())).after();
      assertThat(step.combatPowerAfter()).isEqualByComparingTo(fresh);
    }
    assertThat(path.finalCombatPower().subtract(path.combatPower())).isEqualByComparingTo(deltas);
    assertThat(path.totalCost()).isEqualTo(previous);
    // 같은 입력이면 같은 순서(캐시를 비워도)
    store.saved.clear();
    store.paths.clear();
    var again = service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 5);
    assertThat(again.steps().stream().map(SpecUpGoalService.Step::candidateId).toList())
        .isEqualTo(path.steps().stream().map(SpecUpGoalService.Step::candidateId).toList());
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "computed").count())
        .isEqualTo(2);
    assertThat(
            meters
                .get("loakraken.spec_up.path_compute")
                .tags("pick", "EFFICIENCY", "stop", "STEP_LIMIT")
                .timer()
                .count())
        .isEqualTo(2);
  }

  /** 시세가 없는 단계는 고르지 않고 unpriced에 따로(지금 상태 기준, 전투력이 오르는 것만). 비용을 추측하지 않는다. */
  @Test
  void 시세가_없으면_순서는_비고_뺀_항목으로_준다() {
    var service = service(System::nanoTime);
    var path = service.path("목표테스트", SpecUpGoalService.Pick.DELTA, 30);
    assertThat(path.steps()).isEmpty();
    assertThat(path.stopReasonCode()).isEqualTo("NO_CANDIDATE");
    assertThat(path.finalCombatPower()).isEqualByComparingTo(path.combatPower());
    assertThat(path.unpriced()).hasSize(4); // 보석 칸 4개(1 → 2레벨)
    assertThat(path.unpriced())
        .allMatch(item -> item.cost() == null || item.cost().amount() == null)
        .allMatch(item -> item.deltaCombatPower().signum() > 0);
    assertThat(path.totalCost()).isEqualTo(0L);
  }

  /** 비용 규칙 버전(재련·성장 비용 등)이 바뀌면 같은 캐릭터·시세라도 캐시를 쓰지 않고 다시 계산한다(코드 리뷰 11-01 P3). */
  @Test
  void 비용_규칙_버전이_바뀌면_추천_순서_캐시를_다시_쓰지_않는다() {
    var service = service(System::nanoTime);
    when(auctions.cachedGems()).thenReturn(gemCells(Instant.parse("2026-10-31T12:00:00Z"), false));
    when(specUp.costRuleVersions()).thenReturn("spec-up-v1+t4-refining-v4");
    service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
    service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "computed").count())
        .isEqualTo(1);
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "cache_hit").count())
        .isEqualTo(1);
    when(specUp.costRuleVersions()).thenReturn("spec-up-v1+t4-refining-v5");
    service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "computed").count())
        .isEqualTo(2);
    // 목표 경로도 같은 키 규칙
    service.goal("목표테스트", new BigDecimal("3010"));
    var goals = store.saved.size();
    when(specUp.costRuleVersions()).thenReturn("spec-up-v1+t4-refining-v6");
    service.goal("목표테스트", new BigDecimal("3010"));
    assertThat(store.saved).hasSize(goals + 1);
  }

  /**
   * 추천 순서는 완갑(팔 보호구) 재련도 다음 단계 후보로 둔다(스위치가 켜져 있을 때): 한 단계 = 다음 단계, id·등급·한 단계 비용은 목록 완갑 줄과 같은 규칙,
   * 시뮬레이터에는 ARM_GUARD(목표 단계·등급). 같은 칸이라 다음 단계로 이어 올린다. 스위치를 끄면 완갑은 순서에도 unpriced에도 없다.
   */
  @Test
  void 추천_순서는_완갑_재련을_한_단계씩_이어_고르고_스위치를_끄면_뺀다() {
    equipment =
        List.of(
            new CharacterLoadout.Equipment(
                "완갑",
                "테스트 완갑",
                null,
                "고대",
                new CharacterLoadout.EquipmentDetail(
                    14, null, null, null, null, List.of(), List.of(), List.of(), List.of(), null,
                    List.of(), null, List.of(), null, List.of())));
    var service = service(System::nanoTime);
    when(specUp.armGuardEnabled()).thenReturn(true);
    when(specUp.armGuardGrade(
            org.mockito.ArgumentMatchers.eq("고대"), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn("고대");
    // 한 단계 비용: refine → refine + 1 = (refine + 1) × 10,000골드
    when(specUp.armGuardStepCost(org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenAnswer(
            invocation ->
                com.loakraken.specup.domain.SpecUpCost.refineExpected(
                    ((int) invocation.getArgument(0) + 1) * 10_000L));
    var path = service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
    // 보석은 시세가 없어(cachedGems 비어 있음) 순서에서 빠지고, 완갑만 14 → 15 → 16 → 17로 이어 고른다.
    assertThat(path.steps())
        .extracting(SpecUpGoalService.Step::candidateId)
        .containsExactly("EQUIPMENT_REFINE:0:15", "EQUIPMENT_REFINE:0:16", "EQUIPMENT_REFINE:0:17");
    assertThat(path.steps()).allMatch(step -> step.kind().equals("ARM_GUARD"));
    assertThat(path.steps().get(2).change().level()).isEqualTo(17);
    assertThat(path.steps().get(2).change().grade()).isEqualTo("고대");
    assertThat(path.steps())
        .extracting(SpecUpGoalService.Step::extraCost)
        .containsExactly(150_000L, 160_000L, 170_000L);
    assertThat(path.totalCost()).isEqualTo(480_000L);
    assertThat(path.ruleVersion()).isEqualTo("spec-up-path-v2");

    when(specUp.armGuardEnabled()).thenReturn(false);
    var off = service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
    assertThat(off.steps()).isEmpty();
    assertThat(off.unpriced())
        .noneMatch(item -> item.candidateId().startsWith("EQUIPMENT_REFINE:0:"));
  }

  /** 계산 자리가 모두 차 있으면 기다리지 않고 429 SPEC_UP_BUSY(캐시에 없는 계산만 자리를 씀). */
  @Test
  void 추천_순서_계산_자리가_차_있으면_바로_SPEC_UP_BUSY다() throws Exception {
    var service = service(System::nanoTime);
    var field = SpecUpGoalService.class.getDeclaredField("pathSolves");
    field.setAccessible(true);
    var slots = (java.util.concurrent.Semaphore) field.get(service);
    slots.acquire(slots.availablePermits());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3))
        .isInstanceOf(com.loakraken.common.api.BusinessException.class)
        .satisfies(
            e ->
                assertThat(((com.loakraken.common.api.BusinessException) e).errorCode())
                    .isEqualTo(SpecUpErrorCode.SPEC_UP_BUSY));
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "busy").count()).isEqualTo(1);
  }

  /** 겁화 1~10레벨 칸(값은 레벨 × 1,000골드), 같은 관측 시각. */
  private static List<MarketAuctionService.GemCell> gemCells(Instant at, boolean stale) {
    var cells = new java.util.ArrayList<MarketAuctionService.GemCell>();
    for (int level = 1; level <= 10; level++)
      cells.add(
          new MarketAuctionService.GemCell(
              "겁화",
              level,
              level + "레벨 겁화의 보석",
              new com.loakraken.market.application.AuctionPort.AuctionCell(level * 1_000L, 5, at),
              null,
              MarketAuctionService.Status.READY,
              stale));
    return cells;
  }

  private final RelicBookPriceService books = mock(RelicBookPriceService.class);

  private SpecUpGoalService service(LongSupplier nanoTime) {
    when(specUp.enabled()).thenReturn(true);
    when(auctions.cachedGems()).thenReturn(List.of());
    when(materials.cached()).thenReturn(Optional.empty());
    var gems = new java.util.ArrayList<CharacterLoadout.Gem>();
    for (int slot = 0; slot < 4; slot++)
      gems.add(new CharacterLoadout.Gem(slot, "1레벨 겁화의 보석", null, 1, "고대"));
    var loadout =
        new CharacterLoadout(
            equipment,
            null,
            new CharacterLoadout.Gems(gems, null),
            null,
            new CharacterLoadout.ArkPassive(
                "진화", true, List.of(new CharacterLoadout.PassivePoint("진화", 100, "")), List.of()),
            null,
            List.of());
    var profile =
        new CharacterProfile(
            "목표테스트",
            "목표서버",
            "버서커",
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            null,
            null,
            null,
            null,
            List.of());
    var snapshot =
        new CharacterResult(
            CharacterTestFixture.details(profile, loadout, List.of()),
            Instant.parse("2026-10-12T00:00:00Z"));
    when(simulator.snapshot("목표테스트")).thenReturn(snapshot);
    // 가짜 시뮬레이터: 전투력 = 3000 + (적용한 보석 레벨 합 − 처음 레벨 합).
    when(simulator.simulate(any(CharacterResult.class), anyList()))
        .thenAnswer(
            invocation -> {
              List<CombatPowerSimulationService.Change> changes = invocation.getArgument(1);
              int raised = 0;
              for (var change : changes) raised += change.level() - 1;
              var after = new BigDecimal("3000").add(BigDecimal.valueOf(raised)).setScale(2);
              return new CombatPowerSimulationService.Result(
                  "목표테스트",
                  snapshot.fetchedAt(),
                  new BigDecimal("3000.00"),
                  null,
                  after,
                  after.subtract(new BigDecimal("3000.00")),
                  null,
                  false,
                  List.of(),
                  0,
                  List.of());
            });
    return new SpecUpGoalService(
        specUp, simulator, auctions, books, materials, store, meters, nanoTime);
  }

  private static final class MemoryStore implements SpecUpGoalStorePort {
    final Map<String, SpecUpGoalService.Goal> saved = new HashMap<>();

    @Override
    public Optional<SpecUpGoalService.Goal> find(String key) {
      return Optional.ofNullable(saved.get(key));
    }

    @Override
    public void save(String key, SpecUpGoalService.Goal goal, Duration ttl) {
      saved.put(key, goal);
    }

    final Map<String, SpecUpGoalService.Path> paths = new HashMap<>();

    @Override
    public Optional<SpecUpGoalService.Path> findPath(String key) {
      return Optional.ofNullable(paths.get(key));
    }

    @Override
    public void savePath(String key, SpecUpGoalService.Path path, Duration ttl) {
      paths.put(key, path);
    }
  }
}
