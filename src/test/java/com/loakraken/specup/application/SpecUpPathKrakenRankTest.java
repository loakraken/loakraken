package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 추천 순서 sort=KRAKEN 고르기(PM 총괄 11-15 (d)): 공짜 단계 먼저(Δ점수 큰 순), 그다음 10만 골드당 Δ점수, 같으면 Δ점수·id. 작열 보석 칸은
 * 뺀다.
 */
class SpecUpPathKrakenRankTest {
  @Test
  void 더_드는_비용이_0_이하인_단계가_먼저이고_그다음_골드당_점수가_큰_순이다() {
    // 공짜(0골드) 단계는 Δ가 작아도 비용 있는 단계보다 먼저.
    assertThat(SpecUpGoalService.krakenPreferred(0.5, 0L, "b", 50.0, 1_000L, "a")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(50.0, 1_000L, "a", 0.5, 0L, "b")).isFalse();
    // 공짜끼리는 Δ 큰 순.
    assertThat(SpecUpGoalService.krakenPreferred(2.0, -10L, "b", 1.0, 0L, "a")).isTrue();
    // 비용 있는 단계는 Δ ÷ 비용 큰 순: 1/100 > 5/1000.
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 100L, "b", 5.0, 1_000L, "a")).isTrue();
    // 같은 비율이면 Δ 큰 순, 그것도 같으면 id 순.
    assertThat(SpecUpGoalService.krakenPreferred(2.0, 200L, "b", 1.0, 100L, "a")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 100L, "a", 1.0, 100L, "b")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 100L, "b", 1.0, 100L, "a")).isFalse();
  }

  @Test
  void 작열_보석_칸은_효과_문구나_이름으로_찾는다() {
    var gems =
        new CharacterLoadout.Gems(
            List.of(
                new CharacterLoadout.Gem(0, "8레벨 겁화의 보석", null, 8, "고대"),
                new CharacterLoadout.Gem(1, "8레벨 작열의 보석", null, 8, "고대"),
                new CharacterLoadout.Gem(2, "8레벨 광휘의 보석", null, 8, "고대"),
                new CharacterLoadout.Gem(3, "8레벨 광휘의 보석", null, 8, "고대")),
            new CharacterLoadout.GemEffects(
                "기본 공격력 총합 : 0.80%",
                List.of(
                    new CharacterLoadout.GemSkill(
                        2, "스킬 가", List.of("재사용 대기시간 20.00% 감소"), null, null),
                    new CharacterLoadout.GemSkill(
                        3, "스킬 나", List.of("피해 32.00% 증가"), null, null))));
    var loadout = mock(CharacterLoadout.class);
    when(loadout.gems()).thenReturn(gems);
    assertThat(SpecUpService.cooldownGemSlots(loadout))
        .containsExactlyInAnyOrder("GEM_LEVEL:1", "GEM_LEVEL:2");
  }
}
