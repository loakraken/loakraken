package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.specup.domain.SpecUpCost;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 줄 상세(#554): 목록 비용과 시세 내역이 두 번 어긋나면 틀린 금액을 내보내지 않는다. */
class SpecUpDetailMismatchTest {
  private static final Instant AT = Instant.parse("2026-10-18T00:00:00Z");

  private final SpecUpService specUp = mock(SpecUpService.class);
  private final CombatPowerSimulationService simulator = mock(CombatPowerSimulationService.class);
  private final MarketAuctionService auctions = mock(MarketAuctionService.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SpecUpDetailService details =
      new SpecUpDetailService(
          specUp,
          simulator,
          auctions,
          mock(RelicBookPriceService.class),
          mock(RefiningMaterialPriceService.class),
          meters);

  SpecUpDetailMismatchTest() {
    when(specUp.enabled()).thenReturn(true);
    when(simulator.snapshot(anyString()))
        .thenReturn(mock(CharacterResult.class, RETURNS_DEEP_STUBS));
    // 목록: 빈 칸 10레벨 겁화, 비용 = 그때 즉시 구매 최저가 3,000,000.
    when(specUp.guide(any(CharacterResult.class), any(), anyInt()))
        .thenReturn(guide(SpecUpCost.gem(3_000_000, null, false)));
  }

  @Test
  void 두_번_모두_어긋나면_404로_목록을_다시_부르게_하고_지표를_센다() {
    // 내역을 읽을 때마다 시세가 이미 바뀌어 있다.
    when(auctions.cachedGems()).thenReturn(cells(3_100_000));
    assertThatThrownBy(() -> details.detail("캐릭터", "GEM_LEVEL:0:10"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(SpecUpErrorCode.SPEC_UP_CANDIDATE_NOT_FOUND);
    verify(specUp, times(2)).guide(any(CharacterResult.class), any(), anyInt());
    assertThat(count("retry")).isEqualTo(1);
    assertThat(count("rejected")).isEqualTo(1);
  }

  @Test
  void 두_번째에_맞으면_그_결과를_준다() {
    when(auctions.cachedGems()).thenReturn(cells(3_100_000), cells(3_000_000));
    var detail = details.detail("캐릭터", "GEM_LEVEL:0:10");
    assertThat(detail.market().getFirst().price()).isEqualTo(3_000_000L);
    assertThat(count("retry")).isEqualTo(1);
    assertThat(count("rejected")).isZero();
  }

  @Test
  void 처음부터_맞으면_다시_만들지_않는다() {
    when(auctions.cachedGems()).thenReturn(cells(3_000_000));
    details.detail("캐릭터", "GEM_LEVEL:0:10");
    verify(specUp, times(1)).guide(any(CharacterResult.class), any(), anyInt());
    assertThat(count("retry")).isZero();
  }

  private double count(String outcome) {
    var counter =
        meters.find("loakraken.spec_up.detail_mismatch").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  private static List<MarketAuctionService.GemCell> cells(long price) {
    return List.of(
        new MarketAuctionService.GemCell(
            "겁화",
            10,
            "10레벨 겁화의 보석",
            new AuctionCell(price, 5, AT),
            null,
            MarketAuctionService.Status.READY));
  }

  private static SpecUpService.Guide guide(SpecUpCost cost) {
    var candidate =
        new SpecUpService.Candidate(
            "GEM_LEVEL:0:10",
            "GEM_LEVEL",
            "빈 보석 칸 11개",
            "빈 칸",
            "10레벨 겁화",
            null,
            null,
            null,
            null,
            null,
            "OK",
            new BigDecimal("248.79"),
            new BigDecimal("8.293"),
            cost,
            SpecUpCost.efficiency(new BigDecimal("248.79"), cost.amount()),
            null,
            null,
            null,
            true,
            BigDecimal.ZERO,
            new BigDecimal("1.20"),
            null,
            null,
            "DAMAGE");
    return new SpecUpService.Guide(
        "캐릭터",
        "서버",
        "직업",
        new BigDecimal("3000.00"),
        SpecUpService.RULE_VERSION,
        "sim",
        AT,
        AT,
        "DELTA",
        Integer.MAX_VALUE,
        1,
        List.of(candidate),
        null,
        null,
        true,
        "DISABLED");
  }
}
