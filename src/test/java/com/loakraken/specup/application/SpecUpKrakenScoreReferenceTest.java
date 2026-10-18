package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerRefineModel;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스펙업 Δ크라켄 점수가 비교 기준 B 스펙업 추천 화면의 ▲점수와 같은지(레퍼런스 결과 표, 같은 시각 기준 캐릭터 원문 l01·l05). ▲ = 반올림 전 점수 차를 소수
 * 둘째 자리로. 보석·각인·재련 6부위만 — 완갑 강화·악세 연마·카르마·스톤은 우리 후보에 없다.
 */
class SpecUpKrakenScoreReferenceTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @ParameterizedTest(name = "{0} {1} {2} → {3} ▲{4}")
  @CsvSource({
    // 언새늘(l01) 보석: 스킬 이름(딜 보석) · 목표 레벨
    "l01, GEM, 블레이드 버스트, 7, 98.81",
    "l01, GEM, 블레이드 버스트, 8, 199.79",
    "l01, GEM, 블레이드 버스트, 9, 301.10",
    "l01, GEM, 블레이드 버스트, 10, 402.75",
    "l01, GEM, 블레이드 댄스, 7, 19.99",
    "l01, GEM, 블레이드 댄스, 10, 85.73",
    "l01, GEM, 터닝 슬래쉬, 7, 15.84",
    "l01, GEM, 윈드 컷, 7, 11.69",
    // 언새늘 각인: 이름 · 목표 레벨
    "l01, ENGRAVING, 기습의 대가, 1, 25.71",
    "l01, ENGRAVING, 기습의 대가, 4, 102.85",
    "l01, ENGRAVING, 아드레날린, 4, 24.18",
    "l01, ENGRAVING, 돌격대장, 1, 25.71",
    "l01, ENGRAVING, 원한, 1, 24.91",
    "l01, ENGRAVING, 원한, 4, 99.63",
    // 언새늘 재련: 부위 · 목표 단계
    "l01, REFINE, 무기, 20, 44.92",
    "l01, REFINE, 장갑, 20, 9.90",
    "l01, REFINE, 어깨, 20, 8.76",
    "l01, REFINE, 투구, 20, 8.24",
    "l01, REFINE, 하의, 20, 7.12",
    "l01, REFINE, 상의, 20, 6.58",
    // 널망(l05) 재련
    "l05, REFINE, 무기, 23, 37.04",
    "l05, REFINE, 어깨, 21, 7.41",
    "l05, REFINE, 투구, 21, 6.97",
    "l05, REFINE, 하의, 21, 6.03",
    "l05, REFINE, 상의, 21, 5.56",
    "l05, REFINE, 장갑, 22, 8.56",
  })
  void 레퍼런스_스펙업_추천의_점수_차와_같다(String id, String kind, String target, int level, String expected)
      throws Exception {
    var doc =
        MAPPER.readTree(
            new ClassPathResource("kraken-score/" + id + ".json")
                .getContentAsString(StandardCharsets.UTF_8));
    var input = KrakenScoreInputMapper.from(doc.path("raw"));
    var loadout = ArmoryRawFixtures.loadout(doc.path("raw"));
    var scorer = new SpecUpKrakenScore(tables(), refine(), true);
    double before = KrakenScoreEngine.dealerScore(input, tables());
    var context = new SpecUpKrakenScore.Context(input, before, null);
    var change =
        switch (kind) {
          case "GEM" -> change(Kind.GEM_LEVEL, damageGemSlot(loadout, target), null, level, null);
          case "ENGRAVING" ->
              new Change(
                  Kind.ENGRAVING_LEVEL,
                  null,
                  null,
                  null,
                  level,
                  null,
                  null,
                  null,
                  null,
                  target,
                  null,
                  null,
                  null,
                  null);
          default ->
              change(Kind.EQUIPMENT_REFINE, null, equipmentIndex(loadout, target), null, level);
        };
    var delta = scorer.delta(context, loadout, change);
    assertThat(delta).as("계산 가능").isNotNull();
    // ▲ = 반올림 전 점수 차를 소수 둘째 자리로(화면 점수끼리의 차가 아님 — 레퍼런스 최종 점수도 지금 + 반올림 전 차).
    assertThat(SpecUpKrakenScore.round2(delta))
        .as("반올림 전 차 %s", delta)
        .isEqualByComparingTo(expected);
    // 원본 입력은 그대로다.
    assertThat(KrakenScoreEngine.dealerScore(input, tables())).isEqualTo(before);
  }

  private static Change change(
      Kind kind, Integer slot, Integer index, Integer level, Integer refine) {
    return new Change(
        kind, slot, index, null, level, refine, null, null, null, null, null, null, null, null);
  }

  private static int damageGemSlot(CharacterLoadout loadout, String skill) {
    for (var effect : loadout.gems().effects().skills())
      if (skill.equals(effect.name())
          && !String.join(" ", effect.description()).contains("재사용 대기시간")) return effect.gemSlot();
    throw new AssertionError(skill + " 딜 보석이 없습니다");
  }

  private static int equipmentIndex(CharacterLoadout loadout, String type) {
    for (int i = 0; i < loadout.equipment().size(); i++)
      if (type.equals(loadout.equipment().get(i).type())) return i;
    throw new AssertionError(type + " 장비가 없습니다");
  }

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
