package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CombatPowerRefineModel;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** 스톤 목표 조합의 배치: 점수가 더 오르는 쪽, 같으면 앞 배치(목록과 추천 순서가 같은 배치를 쓴다, PM 총괄 11-15). */
class SpecUpStoneOrientationTest {
  private static final int[] NINE_SEVEN = {9, 7, 3, 2};

  private static SpecUpKrakenScore scorer(double first, double flipped) {
    var scorer = mock(SpecUpKrakenScore.class);
    when(scorer.delta(any(SpecUpKrakenScore.Context.class), any(SpecUpKrakenScore.Edit.class)))
        .thenReturn(null);
    when(scorer.delta(
            any(SpecUpKrakenScore.Context.class),
            argThat(
                (SpecUpKrakenScore.Edit edit) ->
                    edit instanceof SpecUpKrakenScore.Stone stone
                        && stone.levels().equals(Map.of("원한", 3, "아드레날린", 2)))))
        .thenReturn(first);
    when(scorer.delta(
            any(SpecUpKrakenScore.Context.class),
            argThat(
                (SpecUpKrakenScore.Edit edit) ->
                    edit instanceof SpecUpKrakenScore.Stone stone
                        && stone.levels().equals(Map.of("원한", 2, "아드레날린", 3)))))
        .thenReturn(flipped);
    return scorer;
  }

  @Test
  void 점수가_같으면_앞_배치이고_더_오르면_그_배치다() {
    var context = new SpecUpKrakenScore.Context(null, 100.0, null, false);
    var names = List.of("원한", "아드레날린");
    // 같은 점수(전투력만 다른 두 배치): 앞 배치(점수 입력 순서대로 높은 레벨 먼저).
    assertThat(
            SpecUpScoreCandidates.orientation(scorer(1.5, 1.5), context, names, NINE_SEVEN)
                .levels())
        .isEqualTo(Map.of("원한", 3, "아드레날린", 2));
    assertThat(
            SpecUpScoreCandidates.orientation(scorer(1.0, 1.5), context, names, NINE_SEVEN)
                .levels())
        .isEqualTo(Map.of("원한", 2, "아드레날린", 3));
    assertThat(
            SpecUpScoreCandidates.orientation(scorer(1.5, 1.0), context, names, NINE_SEVEN)
                .levels())
        .isEqualTo(Map.of("원한", 3, "아드레날린", 2));
  }

  @Test
  void 점수가_오르지_않는_조합은_배치에서_빠진다() {
    var input = MAPPER.createObjectNode();
    input
        .putObject("armory")
        .putObject("abilityStone")
        .putArray("option")
        .add("원한 Lv.1")
        .add("아드레날린 Lv.1");
    var context = new SpecUpKrakenScore.Context(input, 100.0, null, false);
    var scorer = mock(SpecUpKrakenScore.class);
    // 97돌: 앞 배치 +1.5, 106·107돌: 점수 차 0(둘째 자리 반올림 0) → 비교 기준도 내지 않는다.
    when(scorer.delta(any(SpecUpKrakenScore.Context.class), any(SpecUpKrakenScore.Edit.class)))
        .thenAnswer(
            invocation -> {
              var stone = (SpecUpKrakenScore.Stone) invocation.getArgument(1);
              return stone.levels().equals(Map.of("원한", 3, "아드레날린", 2)) ? 1.5 : 0.001;
            });
    assertThat(SpecUpScoreCandidates.stoneOrientations(scorer, context))
        .containsExactly(Map.of("원한", 3, "아드레날린", 2));
  }

  /** 점수 표시 스위치가 꺼져도 배치는 점수로 고른다(비교 기준은 늘 점수로 배치를 정한다, PM 총괄 11-15). 기준 캐릭터 원문 l01. */
  @Test
  void 점수_표시가_꺼져도_안에서_점수로_배치를_고른다() throws Exception {
    var raw =
        MAPPER
            .readTree(
                new ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
    var snapshot = mock(CharacterResult.class);
    var details = mock(CharacterDetails.class);
    var profile = mock(CharacterProfile.class);
    when(snapshot.krakenScoreInput()).thenReturn(KrakenScoreInputMapper.from(raw));
    when(snapshot.details()).thenReturn(details);
    when(details.profile()).thenReturn(profile);
    when(profile.className()).thenReturn("블레이드");
    when(details.loadout()).thenReturn(ArmoryRawFixtures.loadout(raw));
    var off = new SpecUpKrakenScore(tables(), refine(), false);
    assertThat(off.context(snapshot).unavailable()).isEqualTo("DISABLED");
    var internal = off.internalContext(snapshot);
    assertThat(internal.available()).isTrue();
    var on = new SpecUpKrakenScore(tables(), refine(), true);
    // 표시가 꺼져도 켠 것과 같은 배치.
    assertThat(SpecUpScoreCandidates.stoneOrientations(off, internal))
        .isNotNull()
        .isEqualTo(SpecUpScoreCandidates.stoneOrientations(on, on.context(snapshot)));
  }

  private static final JsonMapper MAPPER = new JsonMapper();

  private static KrakenScoreEngine.Tables tables() throws Exception {
    var doc =
        MAPPER.readTree(
            new ClassPathResource("kraken-score/reference-tables.json")
                .getContentAsString(StandardCharsets.UTF_8));
    return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
  }

  private static CombatPowerRefineModel.Coefficients refine() throws Exception {
    try (var stream =
        new ClassPathResource("combat-power/refine-coefficients.json").getInputStream()) {
      return MAPPER.readValue(stream, CombatPowerRefineModel.Coefficients.class);
    }
  }
}
