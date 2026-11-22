package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.specup.domain.SpecUpCostRules;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 추천 순서 카르마 단계 (f)(spec-up.karma-cp.enabled): 목록 카르마 줄과 같은 id·비용, 단계 뒤 상태, 동점 순서는 맨 뒤. */
class SpecUpKarmaPathTest {
  @Test
  void 카르마_단계는_목록_줄과_같은_id와_지금_레벨에서의_비용이다() {
    var options = SpecUpGoalService.karmaOptions(28);
    assertThat(options)
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly("KARMA_LEVEL:29", "KARMA_LEVEL:30");
    assertThat(options.get(0).cost().amount())
        .isEqualTo(Math.round(SpecUpCostRules.karmaGold(28, 29)));
    assertThat(options.get(1).cost().amount())
        .isEqualTo(Math.round(SpecUpCostRules.karmaGold(28, 30)));
    for (var option : options) {
      assertThat(option.kind()).isEqualTo("KARMA_LEVEL");
      assertThat(option.slot()).isEqualTo("KARMA_LEVEL");
      var change = option.change();
      assertThat(change.kind()).isEqualTo(CombatPowerSimulationService.Kind.ARK_PASSIVE_POINT);
      assertThat(change.tree()).isEqualTo("깨달음");
      assertThat(change.points()).isNull();
      assertThat(change.level()).isNull();
      assertThat(change.karmaLevel()).isEqualTo(Integer.parseInt(option.id().split(":")[1]));
      assertThat(CombatPowerSimulationService.karmaChange(change)).isTrue();
      // 거래 불가: 단계 비용 그대로(앞 단계 품목 빼기 없음), 합계는 단계마다 더한다.
      assertThat(SpecUpGoalService.paidCost(option, Map.of("KARMA_LEVEL", 1L)))
          .isEqualTo(option.cost().amount());
      assertThat(SpecUpGoalService.slotCost(option)).isFalse();
    }
    assertThat(SpecUpGoalService.karmaOptions(30)).isEmpty();
  }

  @Test
  void 카르마_단계_뒤에는_그_레벨에서_다시_내고_30이면_더_내지_않는다() {
    var state =
        new SpecUpGoalService.State(Map.of(), Map.of(), Map.of(), null, Map.of(), null, null, null)
            .withKarma(28);
    assertThat(state.karma()).isEqualTo(28);
    var to29 = SpecUpGoalService.karmaOptions(28).get(0);
    var after = state.after(to29);
    assertThat(after.karma()).isEqualTo(29);
    assertThat(SpecUpGoalService.karmaOptions(after.karma()))
        .extracting(SpecUpGoalService.Option::id)
        .containsExactly("KARMA_LEVEL:30");
    assertThat(after.after(SpecUpGoalService.karmaOptions(29).get(0)).karma()).isNull();
    // 스위치가 꺼졌거나 읽지 못하면(null) 카르마 단계 없음, 이미 30이어도 없음.
    assertThat(state.withKarma(null).karma()).isNull();
    assertThat(state.withKarma(30).karma()).isNull();
  }

  @Test
  void 동점이면_카르마는_각인_뒤_맨_끝이다() {
    assertThat(SpecUpGoalService.candidateOrder("KARMA_LEVEL:30", "ENGRAVING_LEVEL:원한:4"))
        .isPositive();
    assertThat(SpecUpGoalService.candidateOrder("ABILITY_STONE:2:3", "KARMA_LEVEL:30"))
        .isNegative();
  }
}
