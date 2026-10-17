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
            List.of(),
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
    return new SpecUpGoalService(specUp, simulator, auctions, books, materials, store, nanoTime);
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
  }
}
