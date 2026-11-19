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

  {
    // 실제 서비스는 점수 입력을 읽을 수 없을 때 null(앞 배치)이다. 목은 빈 목록(점수가 오르는 조합 없음)을 돌려주므로 null로 둔다.
    when(specUp.stoneOrientations(org.mockito.ArgumentMatchers.any())).thenReturn(null);
  }

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

  /**
   * 연마 단계 스위치가 꺼져 있으면(연마 후보 없음) 목표 경로·추천 순서 캐시 키는 연마 단계를 넣기 전(develop)과 글자까지 같다. 배포 뒤 기존 캐시를 그대로
   * 쓴다(코드 리뷰 #951 P2: 키 끝에 '-'가 붙어 배포 뒤 캐시가 한꺼번에 비었다).
   */
  @Test
  void 연마_단계_스위치가_꺼지면_캐시_키는_연마_단계_전과_같다() {
    var service = service(System::nanoTime);
    service.goal("목표테스트", new BigDecimal("3002"));
    service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 5);
    var fetched = String.valueOf(Instant.parse("2026-10-12T00:00:00Z").toEpochMilli());
    // 서버·이름·목표·수집 시각·보석 시세 시각·이전 시세 칸·재련 시세 시각·각인서 시세 시각·규칙 버전 셋·완갑 스위치.
    assertThat(store.saved.keySet())
        .containsExactly(
            String.join(
                ":",
                "목표서버",
                "목표테스트",
                "3002.00",
                fetched,
                "0",
                "0",
                "0",
                "0",
                SpecUpGoalService.RULE_VERSION,
                CombatPowerSimulationService.RULE_VERSION,
                "null",
                "-"));
    assertThat(store.paths.keySet())
        .containsExactly(
            String.join(
                ":",
                "목표서버",
                "목표테스트",
                "EFFICIENCY",
                "5",
                fetched,
                "0",
                "0",
                "0",
                "0",
                SpecUpGoalService.PATH_RULE_VERSION,
                CombatPowerSimulationService.RULE_VERSION,
                "null",
                "-"));
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
    // 비용을 모두 알면 아는 합 = 합계, 모르는 단계 0(PM 총괄 11-19).
    assertThat(path.knownCostTotal()).isEqualTo(480_000L);
    assertThat(path.unknownCostSteps()).isZero();
    assertThat(path.ruleVersion()).isEqualTo("spec-up-path-v7");

    when(specUp.armGuardEnabled()).thenReturn(false);
    var off = service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
    // 스위치를 끄면 완갑은 순서에 없다. 시세를 모르는 보석 단계는 고를 수 있고(PM 총괄 11-19 (B), 비용 있는 단계 뒤) 합계는 모름이다.
    assertThat(off.steps()).noneMatch(step -> step.kind().equals("ARM_GUARD"));
    assertThat(off.steps()).allMatch(step -> step.extraCost() == null);
    assertThat(off.totalCost()).isNull();
    // 합계는 모름이어도 아는 합(0)과 모르는 단계 수는 준다(웹 'X G + 비용 모름 N단계').
    assertThat(off.knownCostTotal()).isZero();
    assertThat(off.unknownCostSteps()).isEqualTo(off.steps().size());
    // EFFICIENCY는 비용을 모르는 단계도 고르므로 '비용을 몰라 빠진 후보'가 없다(DELTA만 남음).
    assertThat(off.unpriced()).isEmpty();
    var delta = service.path("목표테스트", SpecUpGoalService.Pick.DELTA, 3);
    assertThat(delta.steps()).isEmpty();
    assertThat(delta.unpriced())
        .noneMatch(item -> item.candidateId().startsWith("EQUIPMENT_REFINE:0:"));
  }

  /**
   * 어빌리티 스톤 목표 조합(목록 스톤 줄과 같은 목표·id·비용): 조합마다 목록과 같은 배치 하나(목록 배치를 모르면 앞 배치), 한 단계에 각인
   * 둘(STONE_ENGRAVING 둘)을 바꾼다. 지금과 같은 레벨 조합은 뺀다(PM 총괄 11-15: 뒤집은 배치는 고르지 않음).
   */
  @Test
  void 스톤_목표_조합은_각인_둘을_한_단계로_바꾸고_목록과_같은_id와_비용이다() {
    var levels = new java.util.LinkedHashMap<String, Integer>();
    levels.put("원한", 1);
    levels.put("아드레날린", 1);
    var options =
        SpecUpGoalService.stoneOptions(new SpecUpGoalService.Stone(stoneItem(1, 1), levels));
    // 97·106·107·99·109·1010 하나씩 = 6(앞 배치, PM 총괄 11-19). 99·109·1010돌을 고를지는 경로가 정한다(DELTA·목표 경로는
    // 뺌).
    assertThat(options)
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly(
            "ABILITY_STONE:3:2",
            "ABILITY_STONE:4:1",
            "ABILITY_STONE:4:2",
            "ABILITY_STONE:3:3",
            "ABILITY_STONE:4:3",
            "ABILITY_STONE:4:4");
    for (var option : options) {
      assertThat(option.kind()).isEqualTo("ABILITY_STONE");
      assertThat(option.slot()).isEqualTo("ABILITY_STONE");
      assertThat(option.changes()).hasSize(2);
      assertThat(option.changes())
          .allMatch(change -> change.kind() == CombatPowerSimulationService.Kind.STONE_ENGRAVING);
      assertThat(option.changes())
          .extracting(CombatPowerSimulationService.Change::name)
          .containsExactly("원한", "아드레날린");
    }
    var nineSeven =
        options.stream()
            .filter(option -> option.id().equals("ABILITY_STONE:3:2"))
            .findFirst()
            .get();
    var gold = com.loakraken.specup.domain.SpecUpCostRules.stoneGold("97돌");
    assertThat(nineSeven.cost())
        .isEqualTo(
            gold == null
                ? com.loakraken.specup.domain.SpecUpCost.STONE_COST_UNCONFIRMED
                : com.loakraken.specup.domain.SpecUpCost.stone(gold));
    assertThat(nineSeven.to()).startsWith("97돌 ");
    // 예리한 둔기 스톤도 스톤 단계를 낸다(각인 둘 함께 바꾸기가 독립 계산과 같음, #813).
    var sharp = new java.util.LinkedHashMap<String, Integer>();
    sharp.put("원한", 1);
    sharp.put("예리한 둔기", 1);
    assertThat(SpecUpGoalService.stoneOptions(new SpecUpGoalService.Stone(stoneItem(1, 1), sharp)))
        .hasSize(6)
        .allMatch(option -> option.changes().get(1).name().equals("예리한 둔기"));
    // 지금이 이미 3·2면 그 배치는 빠지고 뒤집은 2·3은 내지 않는다.
    levels.put("원한", 3);
    levels.put("아드레날린", 2);
    assertThat(SpecUpGoalService.stoneOptions(new SpecUpGoalService.Stone(stoneItem(3, 2), levels)))
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly(
            "ABILITY_STONE:4:1",
            "ABILITY_STONE:4:2",
            "ABILITY_STONE:3:3",
            "ABILITY_STONE:4:3",
            "ABILITY_STONE:4:4");
  }

  /**
   * 목록이 뒤집은 배치를 보이면(높은 레벨이 둘째 각인) 추천 순서도 그 배치 하나만 낸다. 같은 점수에 다른 전투력인 반대 배치는 고르지 않는다(PM 총괄 11-15: 계산
   * 검증 표본에서 같은 점수, 전투력 19.56 대 17.67인 두 배치를 id로 갈라 목록에 없는 배치를 골랐음).
   */
  @Test
  void 스톤_배치는_목록과_같은_배치만_낸다() {
    var levels = new java.util.LinkedHashMap<String, Integer>();
    levels.put("원한", 1);
    levels.put("아드레날린", 1);
    // 목록 배치: 97돌은 아드레날린이 높게, 106·107은 원한이 높게(점수 입력의 각인 순서가 반대여도 이름으로 맞춘다).
    var list =
        java.util.List.<java.util.Map<String, Integer>>of(
            java.util.Map.of("아드레날린", 3, "원한", 2),
            java.util.Map.of("원한", 4, "아드레날린", 1),
            java.util.Map.of("원한", 4, "아드레날린", 2));
    var options =
        SpecUpGoalService.stoneOptions(new SpecUpGoalService.Stone(stoneItem(1, 1), levels, list));
    assertThat(options)
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly("ABILITY_STONE:2:3", "ABILITY_STONE:4:1", "ABILITY_STONE:4:2");
    // 목록에 없는 조합(점수가 오르지 않음)은 내지 않는다(비교 기준과 같음).
    assertThat(
            SpecUpGoalService.stoneOptions(
                new SpecUpGoalService.Stone(
                    stoneItem(1, 1),
                    levels,
                    java.util.List.<java.util.Map<String, Integer>>of(
                        java.util.Map.of("원한", 3, "아드레날린", 2)))))
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly("ABILITY_STONE:3:2");
    // 점수 입력과 툴팁의 각인 이름이 갈라지면(배치가 이 스톤과 이름이 다름) 스톤 단계를 빼지 않고 앞 배치로(코드 리뷰 #975 P3).
    assertThat(
            SpecUpGoalService.stoneOptions(
                new SpecUpGoalService.Stone(
                    stoneItem(1, 1),
                    levels,
                    java.util.List.<java.util.Map<String, Integer>>of(
                        java.util.Map.of("다른 각인", 3, "아드레날린", 2)))))
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly(
            "ABILITY_STONE:3:2",
            "ABILITY_STONE:4:1",
            "ABILITY_STONE:4:2",
            "ABILITY_STONE:3:3",
            "ABILITY_STONE:4:3",
            "ABILITY_STONE:4:4");
    // 점수가 오르는 조합이 하나도 없으면(빈 목록) 스톤 단계가 없다.
    assertThat(
            SpecUpGoalService.stoneOptions(
                new SpecUpGoalService.Stone(stoneItem(1, 1), levels, java.util.List.of())))
        .isEmpty();
    // 단계를 지나도(스톤 상태가 바뀌어도) 같은 배치를 지킨다.
    var after = new java.util.LinkedHashMap<String, Integer>();
    after.put("원한", 2);
    after.put("아드레날린", 3);
    assertThat(
            SpecUpGoalService.stoneOptions(
                new SpecUpGoalService.Stone(stoneItem(2, 3), after, list)))
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly("ABILITY_STONE:4:1", "ABILITY_STONE:4:2");
  }

  /** 추천 순서는 스톤 단계도 고르고, 단계 changes(각인 둘)를 같은 칸은 마지막만 남겨 다시 넣으면 그 단계 전투력과 같다. */
  @Test
  void 추천_순서는_스톤_단계를_고르고_단계_changes로_다시_재면_같다() {
    equipment = List.of(stoneItem(1, 1));
    var service = service(System::nanoTime);
    var path = service.path("목표테스트", SpecUpGoalService.Pick.DELTA, 2);
    assertThat(path.steps()).isNotEmpty();
    var first = path.steps().getFirst();
    assertThat(first.kind()).isEqualTo("ABILITY_STONE");
    assertThat(first.changes()).hasSize(2);
    assertThat(first.change()).isEqualTo(first.changes().getFirst());
    var bySlot = new java.util.LinkedHashMap<String, List<CombatPowerSimulationService.Change>>();
    for (var step : path.steps()) {
      bySlot.put(
          step.candidateId().substring(0, step.candidateId().lastIndexOf(':')), step.changes());
      var all = new java.util.ArrayList<CombatPowerSimulationService.Change>();
      bySlot.values().forEach(all::addAll);
      var fresh = simulator.simulate(simulator.snapshot("목표테스트"), all).after();
      assertThat(step.combatPowerAfter()).isEqualByComparingTo(fresh);
    }
  }

  /**
   * 핫픽스 v0.3.9.2(PM 총괄 11-09, 11-19 유지): 전투력 우선(DELTA)은 Δ만 보고 고르므로 99·109·1010돌(기대 비용 약
   * 1,723만·7.4억·334억)을 고르면 첫 단계부터 누적 비용이 비현실적이 된다. DELTA와 목표 경로는 이 셋을 빼고, 추천 순서 EFFICIENCY·KRAKEN은
   * 넣지만 비용당 이득으로 고르므로 다른 단계보다 앞에 오지 않는다.
   */
  @Test
  void 전투력_우선_추천_순서와_목표_경로는_비현실적인_스톤을_고르지_않는다() {
    equipment = List.of(stoneItem(1, 1));
    var service = service(System::nanoTime);
    var runs = new java.util.LinkedHashMap<String, List<SpecUpGoalService.Step>>();
    for (var pick : SpecUpGoalService.Pick.values())
      runs.put(pick.name(), service.path("목표테스트", pick, 30).steps());
    runs.put("GOAL", service.goal("목표테스트", new BigDecimal("6000")).steps());
    for (var run : runs.entrySet()) {
      assertThat(run.getValue()).as(run.getKey()).isNotEmpty();
      boolean excluded = run.getKey().equals("DELTA") || run.getKey().equals("GOAL");
      boolean seenExtreme = false;
      for (var step : run.getValue()) {
        boolean extreme = SpecUpScoreCandidates.extremeStone(step.candidateId());
        if (excluded) {
          assertThat(extreme).as(run.getKey() + " " + step.candidateId()).isFalse();
          // 107돌 기대 비용 약 282만이 가장 큰 스톤 비용이다(공식 확률 공개 표, PM 총괄 11-19 (e)).
          if (step.cost() != null && step.cost().amount() != null)
            assertThat(step.cost().amount())
                .as(run.getKey() + " " + step.candidateId())
                .isLessThanOrEqualTo(3_000_000L);
          if (step.cumulativeCost() != null)
            assertThat(step.cumulativeCost()).as(run.getKey() + " 누적").isLessThan(100_000_000L);
        } else if (extreme) seenExtreme = true;
        else if (seenExtreme)
          // 비현실적 스톤 뒤에는 비율 없는 단계(공짜·비용 모름, 규칙 B)만 온다: 비용 있는 단계 가운데 비용당 이득이 가장 작아 맨 뒤.
          assertThat(step.extraCost() == null || step.extraCost() <= 0)
              .as(run.getKey() + " " + step.candidateId() + " 앞에 비현실적 스톤")
              .isTrue();
      }
      // EFFICIENCY·KRAKEN은 99·109·1010돌을 넣는다(PM 총괄 11-19).
      if (!excluded) assertThat(seenExtreme).as(run.getKey() + " 99·109·1010돌").isTrue();
    }
  }

  /**
   * 목표 경로도 추천 순서와 같은 단계 종류를 고른다(v3, 계산 검증 11-09): 전에는 목표 경로가 스톤 단계를 쓰지 않아 스톤으로만 닿는 목표가 '더 올릴 수
   * 없음'이었다. 닿지 않는 목표(6000)면 오르는 단계를 다 고르므로 스톤 단계도 들어 있어야 한다.
   */
  @Test
  void 목표_경로도_어빌리티_스톤_단계를_고른다() {
    equipment = List.of(stoneItem(1, 1));
    var goal = service(System::nanoTime).goal("목표테스트", new BigDecimal("6000"));
    assertThat(goal.steps()).extracting(SpecUpGoalService.Step::kind).contains("ABILITY_STONE");
    for (var step : goal.steps())
      if ("ABILITY_STONE".equals(step.kind()))
        assertThat(step.candidateId())
            .isIn(
                "ABILITY_STONE:3:2",
                "ABILITY_STONE:2:3",
                "ABILITY_STONE:4:1",
                "ABILITY_STONE:1:4",
                "ABILITY_STONE:4:2",
                "ABILITY_STONE:2:4");
  }

  /**
   * 스톤 단계가 이어질 때(PM 총괄 11-19, 비교 기준과 같음): 추천 순서 EFFICIENCY·KRAKEN은 스톤마다 새 스톤을 통째로 사므로 더 드는 비용이 그 스톤
   * 비용 전부이고, 합계는 단계마다 낸 비용(더 드는 비용)의 합이다. DELTA와 목표 경로는 예전 규칙(칸마다 마지막 단계 비용, 뒤 스톤은 차이).
   */
  @Test
  void 스톤_단계가_이어지면_추천_순서는_스톤마다_전부_더하고_DELTA와_목표_경로는_마지막_스톤만_센다() {
    equipment = List.of(stoneItem(1, 1));
    var service = service(System::nanoTime);
    var runs = new java.util.LinkedHashMap<String, List<SpecUpGoalService.Step>>();
    for (var pick : SpecUpGoalService.Pick.values())
      runs.put(pick.name(), service.path("목표테스트", pick, 30).steps());
    runs.put("GOAL", service.goal("목표테스트", new BigDecimal("6000")).steps());
    int multiStone = 0;
    for (var run : runs.entrySet()) {
      boolean baseline = !"DELTA".equals(run.getKey()) && !"GOAL".equals(run.getKey());
      var slots = new java.util.LinkedHashMap<String, Long>();
      long others = 0;
      Long paid = 0L;
      int stones = 0;
      for (var step : run.getValue()) {
        var amount = step.cost() == null ? null : step.cost().amount();
        String slot =
            "ABILITY_STONE".equals(step.kind())
                ? "ABILITY_STONE"
                : step.candidateId().startsWith("GEM_LEVEL:")
                    ? step.candidateId().substring(0, step.candidateId().lastIndexOf(':'))
                    : null;
        if ("ABILITY_STONE".equals(step.kind())) {
          stones++;
          if (baseline)
            assertThat(step.extraCost())
                .as(run.getKey() + " " + step.candidateId() + " 스톤 단계 비용은 전부")
                .isEqualTo(amount);
          else if (amount != null && slots.containsKey(slot) && slots.get(slot) != null)
            assertThat(step.extraCost())
                .as(run.getKey() + " " + step.candidateId() + " 더 드는 비용")
                .isEqualTo(amount - slots.get(slot));
        }
        Long expected;
        if (baseline) {
          paid = paid == null || step.extraCost() == null ? null : paid + step.extraCost();
          expected = paid;
        } else {
          if (slot != null) slots.put(slot, amount);
          else if (amount != null) others += amount;
          if (amount == null && slot == null) others = Long.MIN_VALUE / 2;
          expected =
              others < 0 || slots.containsValue(null)
                  ? null
                  : others + slots.values().stream().mapToLong(Long::longValue).sum();
        }
        assertThat(step.cumulativeCost())
            .as(run.getKey() + " " + step.order() + " " + step.candidateId() + " 누적")
            .isEqualTo(expected);
      }
      if (stones >= 2) multiStone++;
    }
    // 이 픽스처에서 스톤 단계가 둘 이상인 경로가 있어야 규칙을 시험한다.
    assertThat(multiStone).as("스톤 단계가 둘 이상인 경로 수").isPositive();
  }

  /**
   * 실제 원문 스톤(감소 줄이 셋째로 붙음)에서도 스톤 상태를 만든다. 감소 줄을 각인으로 세면 줄이 셋이라 스톤 단계가 하나도 나오지 않았다(계산 검증 11-01 P1).
   */
  @Test
  void 실제_원문_스톤은_감소_줄을_빼고_각인_둘로_스톤_단계를_낸다() throws Exception {
    var raw =
        new tools.jackson.databind.json.JsonMapper()
            .readTree(
                new org.springframework.core.io.ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .path("raw");
    var loadout = com.loakraken.character.infrastructure.ArmoryRawFixtures.loadout(raw);
    var lines =
        loadout.equipment().stream()
            .filter(item -> item != null && "어빌리티 스톤".equals(item.type()))
            .findFirst()
            .orElseThrow()
            .detail()
            .stoneEngravings();
    // 이 원문은 감소 줄이 있어야 이 테스트가 의미가 있다.
    assertThat(lines)
        .extracting(CharacterLoadout.StoneEngraving::name)
        .anyMatch(SpecUpGoalService.STONE_PENALTIES::contains);
    var state = SpecUpGoalService.State.of(loadout, false, true);
    assertThat(state.stone()).isNotNull();
    assertThat(state.stone().levels()).hasSize(2);
    assertThat(state.stone().levels().keySet())
        .noneMatch(SpecUpGoalService.STONE_PENALTIES::contains);
    assertThat(SpecUpGoalService.stoneOptions(state.stone())).isNotEmpty();
    // 목표 경로 상태(stone=false)에는 스톤이 없다.
    assertThat(SpecUpGoalService.State.of(loadout, false, false).stone()).isNull();
  }

  private static CharacterLoadout.Equipment stoneItem(int first, int second) {
    return new CharacterLoadout.Equipment(
        "어빌리티 스톤",
        "테스트 스톤",
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(
                new CharacterLoadout.StoneEngraving("원한", first),
                new CharacterLoadout.StoneEngraving("아드레날린", second)),
            List.of(),
            List.of(),
            null,
            List.of(),
            null,
            List.of(),
            null,
            List.of()));
  }

  /** 계산 자리가 모두 차 있으면 기다리지 않고 429 SPEC_UP_BUSY(캐시에 없는 계산만 자리를 씀). */
  @Test
  void 추천_순서_계산_자리가_차_있으면_바로_SPEC_UP_BUSY다() throws Exception {
    var service = service(System::nanoTime);
    var field = SpecUpGoalService.class.getDeclaredField("solves");
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

  /** 목표 경로 계산도 자리(2)가 모두 차 있으면 기다리지 않고 429 SPEC_UP_BUSY. 끝난 계산은 자리를 돌려준다(보안 점검 11-13). */
  @Test
  void 목표_경로_계산_자리가_차_있으면_바로_SPEC_UP_BUSY이고_끝나면_자리를_돌려준다() throws Exception {
    var service = service(System::nanoTime);
    var field = SpecUpGoalService.class.getDeclaredField("solves");
    field.setAccessible(true);
    var slots = (java.util.concurrent.Semaphore) field.get(service);
    int permits = slots.availablePermits();
    assertThat(permits).isEqualTo(2);
    slots.acquire(permits);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.goal("목표테스트", new BigDecimal("6000")))
        .isInstanceOf(com.loakraken.common.api.BusinessException.class)
        .satisfies(
            e ->
                assertThat(((com.loakraken.common.api.BusinessException) e).errorCode())
                    .isEqualTo(SpecUpErrorCode.SPEC_UP_BUSY));
    assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "busy").count()).isEqualTo(1);

    slots.release(permits);
    service.goal("목표테스트", new BigDecimal("6000"));
    assertThat(slots.availablePermits()).isEqualTo(2);
    assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "computed").count())
        .isEqualTo(1);
  }

  /** 추천 순서와 목표 경로는 계산 자리 2개를 함께 쓴다(운영 4GB, PM 11-13): 추천 순서가 둘 다 쓰면 목표 경로도 바로 SPEC_UP_BUSY. */
  @Test
  void 추천_순서와_목표_경로는_계산_자리_2개를_함께_쓴다() throws Exception {
    var service = service(System::nanoTime);
    var field = SpecUpGoalService.class.getDeclaredField("solves");
    field.setAccessible(true);
    var slots = (java.util.concurrent.Semaphore) field.get(service);
    assertThat(slots.availablePermits()).isEqualTo(2);
    slots.acquire(2);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.goal("목표테스트", new BigDecimal("6000")))
        .isInstanceOf(com.loakraken.common.api.BusinessException.class)
        .satisfies(
            e ->
                assertThat(((com.loakraken.common.api.BusinessException) e).errorCode())
                    .isEqualTo(SpecUpErrorCode.SPEC_UP_BUSY));
    slots.release(2);
  }

  /**
   * 실제 경합: 추천 순서 계산 둘이 자리 2개를 잡고 있으면(계산 중) 세 번째 호출은 추천 순서든 목표 경로든 바로 429 SPEC_UP_BUSY. 계산이 끝나면 목표
   * 경로가 자리를 잡아 계산한다.
   */
  @Test
  void 추천_순서_계산_둘이_자리를_쓰는_동안_세_번째_호출은_종류와_상관없이_바로_SPEC_UP_BUSY다() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(2);
    var release = new java.util.concurrent.CountDownLatch(1);
    var holding = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    LongSupplier clock =
        () -> {
          var name = Thread.currentThread().getName();
          // 계산 자리 안에서 처음 읽는 시각: 추천 순서 계산 스레드는 여기서 놓아줄 때까지 기다린다.
          if (name.startsWith("seat-path-") && holding.add(name)) {
            entered.countDown();
            try {
              // 놓아주지 못하고 시간이 다 되면 그냥 계산을 이어가지 않고 실패로 남긴다(조용히 통과하지 않게).
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("계산 자리 놓아주기 대기 시간 초과");
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new AssertionError("계산 자리 놓아주기 대기 중 중단", exception);
            }
          }
          return System.nanoTime();
        };
    var service = service(clock);
    var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
    var first =
        new Thread(
            () -> {
              try {
                service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
              } catch (Throwable error) {
                errors.add(error);
              }
            },
            "seat-path-1");
    var second =
        new Thread(
            () -> {
              try {
                service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 4);
              } catch (Throwable error) {
                errors.add(error);
              }
            },
            "seat-path-2");
    first.start();
    second.start();
    try {
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
          .as("추천 순서 계산 둘이 자리를 잡음")
          .isTrue();

      for (org.assertj.core.api.ThrowableAssert.ThrowingCallable third :
          List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
              () -> service.goal("목표테스트", new BigDecimal("6000")),
              () -> service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 5)))
        org.assertj.core.api.Assertions.assertThatThrownBy(third)
            .isInstanceOf(com.loakraken.common.api.BusinessException.class)
            .satisfies(
                e ->
                    assertThat(((com.loakraken.common.api.BusinessException) e).errorCode())
                        .isEqualTo(SpecUpErrorCode.SPEC_UP_BUSY));
    } finally {
      release.countDown();
      first.join(10_000);
      second.join(10_000);
    }
    assertThat(first.isAlive()).as("추천 순서 계산 1이 끝남").isFalse();
    assertThat(second.isAlive()).as("추천 순서 계산 2가 끝남").isFalse();
    assertThat(errors).isEmpty();
    // 자리가 비면 목표 경로가 계산한다. 계산 시간은 추천 순서처럼 타이머(p95·p99)로 남는다.
    service.goal("목표테스트", new BigDecimal("6000"));
    assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "computed").count())
        .isEqualTo(1);
    var goalTimer = meters.find("loakraken.spec_up.goal_compute").timer();
    assertThat(goalTimer).as("목표 경로 계산 시간 타이머").isNotNull();
    assertThat(goalTimer.count()).isEqualTo(1);
    assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "busy").count()).isEqualTo(1);
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "busy").count()).isEqualTo(1);
  }

  /**
   * 클라이언트당 계산 자리 1개(보안 11-16 강화 (a)): 한 클라이언트가 계산 중이면 그 클라이언트의 다른 계산(추천 순서·목표 경로)은 기다리지 않고 429
   * SPEC_UP_BUSY(outcome=client_busy)이고, 다른 클라이언트는 남은 자리로 계산한다. 계산이 끝나면 그 클라이언트도 다시 계산한다.
   */
  @Test
  void 한_클라이언트는_계산_자리를_하나만_쓴다() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var holding = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    LongSupplier clock =
        () -> {
          var name = Thread.currentThread().getName();
          if (name.equals("client-a-path") && holding.add(name)) {
            entered.countDown();
            try {
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("계산 자리 놓아주기 대기 시간 초과");
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new AssertionError("계산 자리 놓아주기 대기 중 중단", exception);
            }
          }
          return System.nanoTime();
        };
    var current = new ThreadLocal<String>();
    var service = service(clock, current::get);
    var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
    var first =
        new Thread(
            () -> {
              current.set("client-a");
              try {
                service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 3);
              } catch (Throwable error) {
                errors.add(error);
              }
            },
            "client-a-path");
    first.start();
    try {
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
          .as("클라이언트 a의 추천 순서 계산이 자리를 잡음")
          .isTrue();
      current.set("client-a");
      for (org.assertj.core.api.ThrowableAssert.ThrowingCallable again :
          List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
              () -> service.goal("목표테스트", new BigDecimal("6000")),
              () -> service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 5)))
        org.assertj.core.api.Assertions.assertThatThrownBy(again)
            .isInstanceOf(com.loakraken.common.api.BusinessException.class)
            .satisfies(
                e ->
                    assertThat(((com.loakraken.common.api.BusinessException) e).errorCode())
                        .isEqualTo(SpecUpErrorCode.SPEC_UP_BUSY));
      assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "client_busy").count())
          .isEqualTo(1);
      assertThat(meters.counter("loakraken.spec_up.path", "outcome", "client_busy").count())
          .isEqualTo(1);
      // 다른 클라이언트는 남은 자리로 계산한다.
      current.set("client-b");
      service.goal("목표테스트", new BigDecimal("6000"));
      assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "computed").count())
          .isEqualTo(1);
    } finally {
      release.countDown();
      first.join(10_000);
      current.remove();
    }
    assertThat(first.isAlive()).isFalse();
    assertThat(errors).isEmpty();
    // 끝나면 클라이언트 a도 다시 계산한다(자리를 돌려줌).
    current.set("client-a");
    service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, 5);
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "computed").count())
        .isEqualTo(2);
    current.remove();
  }

  /**
   * 동시 요청 8개(같은 클라이언트, 서로 다른 계산): 자리를 잡는 것은 정확히 하나이고 나머지 7개는 기다리지 않고 client_busy다. 자리를 잡은 계산은 나머지가
   * 모두 거절될 때까지 붙잡아 둔다(겹침을 강제).
   */
  @Test
  void 같은_클라이언트의_동시_요청_8개_중_자리는_하나만_잡는다() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var holding = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    LongSupplier clock =
        () -> {
          var name = Thread.currentThread().getName();
          if (name.startsWith("burst-") && holding.add("held")) {
            entered.countDown();
            try {
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("계산 자리 놓아주기 대기 시간 초과");
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new AssertionError("계산 자리 놓아주기 대기 중 중단", exception);
            }
          }
          return System.nanoTime();
        };
    var service = service(clock, () -> "client-z");
    var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
    var threads = new java.util.ArrayList<Thread>();
    for (int limit = 3; limit <= 10; limit++) {
      int l = limit;
      threads.add(
          new Thread(
              () -> {
                try {
                  service.path("목표테스트", SpecUpGoalService.Pick.EFFICIENCY, l);
                } catch (Throwable error) {
                  errors.add(error);
                }
              },
              "burst-" + limit));
    }
    threads.forEach(Thread::start);
    try {
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (errors.size() < 7 && System.nanoTime() < deadline) Thread.onSpinWait();
    } finally {
      release.countDown();
      for (var thread : threads) thread.join(10_000);
    }
    assertThat(errors)
        .hasSize(7)
        .allSatisfy(
            e ->
                assertThat(((com.loakraken.common.api.BusinessException) e).errorCode())
                    .isEqualTo(SpecUpErrorCode.SPEC_UP_BUSY));
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "client_busy").count())
        .isEqualTo(7);
    assertThat(meters.counter("loakraken.spec_up.path", "outcome", "computed").count())
        .isEqualTo(1);
  }

  /** 전체 자리가 차서 거절된 클라이언트는 자리를 쥔 것으로 남지 않는다(자리가 비면 바로 계산, client_busy 아님). */
  @Test
  void 전체_자리가_차서_거절된_클라이언트는_자리를_쥔_것으로_남지_않는다() throws Exception {
    var service = service(System::nanoTime, () -> "client-c");
    var field = SpecUpGoalService.class.getDeclaredField("solves");
    field.setAccessible(true);
    var slots = (java.util.concurrent.Semaphore) field.get(service);
    slots.acquire(2);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.goal("목표테스트", new BigDecimal("6000")))
        .isInstanceOf(com.loakraken.common.api.BusinessException.class);
    assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "busy").count()).isEqualTo(1);
    slots.release(2);
    service.goal("목표테스트", new BigDecimal("6000"));
    assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "computed").count())
        .isEqualTo(1);
    assertThat(meters.counter("loakraken.spec_up.goal", "outcome", "client_busy").count()).isZero();
    assertThat(slots.availablePermits()).isEqualTo(2);
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
    return service(nanoTime, () -> null);
  }

  private SpecUpGoalService service(
      LongSupplier nanoTime, java.util.function.Supplier<String> client) {
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
        specUp, simulator, auctions, books, materials, store, meters, nanoTime, client);
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
