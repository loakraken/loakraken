package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 추천 순서 sort=KRAKEN 고르기(PM 총괄 11-19, 비교 기준의 고르기 규칙): 비율(Δ점수 ÷ 더 드는 비용)이 있는 단계가 먼저(비율 큰 순), 공짜·비용
 * 모름은 비율이 없어 그 뒤. 같으면 Δ점수 큰 순, 더 드는 비용 낮은 순(모름은 가장 큼), id 순. 작열 보석 칸은 뺀다.
 */
class SpecUpPathKrakenRankTest {
  @Test
  void 비율이_있는_단계가_먼저이고_공짜와_비용_모름은_그_뒤다() {
    // (A) 공짜(0골드) 단계는 Δ가 커도 비용 있는 단계 뒤.
    assertThat(SpecUpGoalService.krakenPreferred(0.5, 1_000L, "b", 50.0, 0L, "a")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(50.0, 0L, "a", 0.5, 1_000L, "b")).isFalse();
    // (B) 비용 모름도 비용 있는 단계 뒤.
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 100L, "b", 9.0, null, "a")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(9.0, null, "a", 1.0, 100L, "b")).isFalse();
    // 비율 없는 것끼리는 Δ 큰 순.
    assertThat(SpecUpGoalService.krakenPreferred(2.0, null, "b", 1.0, 0L, "a")).isTrue();
    // Δ도 같으면 더 드는 비용 낮은 순: 공짜가 모름보다 먼저, 더 싼 공짜가 먼저.
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 0L, "b", 1.0, null, "a")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(1.0, null, "a", 1.0, 0L, "b")).isFalse();
    assertThat(SpecUpGoalService.krakenPreferred(1.0, -10L, "b", 1.0, 0L, "a")).isTrue();
    // 비용 있는 단계는 Δ ÷ 비용 큰 순: 1/100 > 5/1000.
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 100L, "b", 5.0, 1_000L, "a")).isTrue();
    // 같은 비율이면 Δ 큰 순, 그것도 같으면 id 순.
    assertThat(SpecUpGoalService.krakenPreferred(2.0, 200L, "b", 1.0, 100L, "a")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 100L, "a", 1.0, 100L, "b")).isTrue();
    assertThat(SpecUpGoalService.krakenPreferred(1.0, 100L, "b", 1.0, 100L, "a")).isFalse();
    // 모름끼리 Δ 같으면 id 순.
    assertThat(SpecUpGoalService.krakenPreferred(1.0, null, "a", 1.0, null, "b")).isTrue();
  }

  /**
   * 마지막 동점(같은 비율·Δ·비용)은 id 문자열이 아니라 비교 기준의 후보 순서(PM 총괄 11-19 (D)): 보석 → 재련·상급 재련·품질 → 장신구(연마) → 스톤
   * → 각인, 같은 종류는 칸 번호를 숫자로, 스톤은 목표 97 → 106 → 107.
   */
  @Test
  void 동점은_후보_순서로_가른다() {
    // 칸 번호는 숫자로: POLISH:9가 POLISH:10보다 먼저(문자열이면 거꾸로).
    assertThat(SpecUpGoalService.candidateOrder("POLISH:9:HH", "POLISH:10:HH")).isNegative();
    assertThat(SpecUpGoalService.candidateOrder("GEM_LEVEL:2:9", "GEM_LEVEL:10:9")).isNegative();
    // 스톤은 97(레벨 2·3) → 106(1·4) → 107(2·4), 배치 방향과 상관없이.
    assertThat(SpecUpGoalService.candidateOrder("ABILITY_STONE:2:3", "ABILITY_STONE:1:4"))
        .isNegative();
    assertThat(SpecUpGoalService.candidateOrder("ABILITY_STONE:4:1", "ABILITY_STONE:4:2"))
        .isNegative();
    assertThat(SpecUpGoalService.candidateOrder("ABILITY_STONE:3:2", "ABILITY_STONE:2:3"))
        .isNotZero();
    // 종류 순: 보석 → 재련 → 상급 재련 → 품질 → 연마 → 스톤 → 각인.
    var ordered =
        List.of(
            "GEM_LEVEL:9:10",
            "EQUIPMENT_REFINE:0:20",
            "EQUIPMENT_ADVANCED_REFINE:0:30",
            "EQUIPMENT_QUALITY:0:100",
            "POLISH:6:HH",
            "ABILITY_STONE:2:3",
            "ENGRAVING_LEVEL:원한:4");
    for (int k = 0; k + 1 < ordered.size(); k++)
      assertThat(SpecUpGoalService.candidateOrder(ordered.get(k), ordered.get(k + 1)))
          .as(ordered.get(k) + " < " + ordered.get(k + 1))
          .isNegative();
    // 재련·상급 재련·품질은 한 묶음이라 칸 번호가 먼저(코드 리뷰 #993 P3): 0번 무기 품질이 3번 재련보다 먼저.
    assertThat(SpecUpGoalService.candidateOrder("EQUIPMENT_QUALITY:0:100", "EQUIPMENT_REFINE:3:20"))
        .isNegative();
    // 각인은 캐릭터 각인 목록 순서(계산 검증 11-19 P3): 목록에서 앞선 각인이 먼저, 이름 문자열 순이 아님.
    var engravings = java.util.Map.of("돌격대장", 1, "기습의 대가", 2);
    assertThat(
            SpecUpGoalService.candidateOrder(
                "ENGRAVING_LEVEL:돌격대장:1", "ENGRAVING_LEVEL:기습의 대가:1", engravings))
        .isNegative();
    assertThat(
            SpecUpGoalService.krakenPreferred(
                25.71,
                1_000L,
                "ENGRAVING_LEVEL:기습의 대가:1",
                25.71,
                1_000L,
                "ENGRAVING_LEVEL:돌격대장:1",
                engravings))
        .isFalse();
    // 고르기에서도: 같은 비율·Δ·비용이면 POLISH:9가 먼저(KRAKEN).
    assertThat(
            SpecUpGoalService.krakenPreferred(1.0, 100L, "POLISH:9:HH", 1.0, 100L, "POLISH:10:HH"))
        .isTrue();
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
