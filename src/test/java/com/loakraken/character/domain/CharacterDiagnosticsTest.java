package com.loakraken.character.domain;

import static com.loakraken.character.domain.CharacterDiagnostics.Category.*;
import static com.loakraken.character.domain.CharacterDiagnostics.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterDiagnosticsTest {
  @Test
  void 미제공_정보를_비활성이나_미착용으로_단정하지_않는다() {
    var report =
        CharacterDiagnostics.from(
            CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, null)));
    assertThat(report.ruleVersion()).isEqualTo("observed-v1");
    assertThat(report.findings())
        .extracting(CharacterDiagnostics.Finding::code)
        .containsExactly(
            EQUIPMENT_NOT_OBSERVED,
            GEMS_UNAVAILABLE,
            CARDS_UNAVAILABLE,
            ENGRAVINGS_UNAVAILABLE,
            ARK_PASSIVE_UNAVAILABLE,
            ARK_GRID_UNAVAILABLE);
    assertThat(report.findings()).allMatch(finding -> finding.category() == DATA_GAP);
    assertThat(report.findings().getFirst().observedCount()).isZero();
    assertThat(report.findings().subList(1, 6))
        .allMatch(finding -> finding.observedCount() == null);
  }

  @Test
  void 빈_목록은_미제공_섹션과_다르게_표현한다() {
    var analysis =
        new CharacterAnalysis(
            1,
            new CharacterAnalysis.GemSummary(0, 0, null, null, null, List.of()),
            new CharacterAnalysis.CardSummary(0, 0, 0),
            new CharacterAnalysis.EngravingSummary(0, 0, 0),
            new CharacterAnalysis.PassiveSummary(true, List.of()),
            new CharacterAnalysis.GridSummary(0, 0, 0, 0));
    var report = CharacterDiagnostics.from(analysis);
    assertThat(report.findings())
        .extracting(CharacterDiagnostics.Finding::code)
        .containsExactly(GEMS_NOT_OBSERVED, CARDS_NOT_OBSERVED, ARK_GRID_NOT_OBSERVED);
    assertThat(report.findings()).allMatch(finding -> finding.observedCount() == 0);
  }

  @Test
  void 알_수_없는_수치와_상태를_근거와_함께_설명한다() {
    var analysis =
        new CharacterAnalysis(
            1,
            new CharacterAnalysis.GemSummary(3, 2, 7, 7, new BigDecimal("7.00"), List.of()),
            new CharacterAnalysis.CardSummary(3, 1, 5),
            new CharacterAnalysis.EngravingSummary(0, 0, 5),
            new CharacterAnalysis.PassiveSummary(
                null,
                List.of(
                    new CharacterAnalysis.PointValue("진화", 0),
                    new CharacterAnalysis.PointValue("깨달음", null))),
            new CharacterAnalysis.GridSummary(1, 0, 0, 2));
    var findings = CharacterDiagnostics.from(analysis).findings();
    assertThat(findings)
        .extracting(CharacterDiagnostics.Finding::code)
        .containsExactly(
            GEM_LEVEL_UNKNOWN,
            CARD_AWAKENING_UNKNOWN,
            ARK_PASSIVE_STATE_UNKNOWN,
            ARK_PASSIVE_POINT_UNKNOWN,
            ARK_GRID_GEM_STATE_UNKNOWN);
    assertThat(findings)
        .extracting(CharacterDiagnostics.Finding::observedCount)
        .containsExactly(2, 1, null, null, 2);
    assertThat(findings.get(3).evidencePath())
        .isEqualTo("/data/analysis/arkPassive/points/1/value");
    assertThat(findings).allMatch(finding -> finding.category() == DATA_GAP);
  }

  @Test
  void 관측된_비활성은_세팅_확인으로_분류하되_미확인과_섞지_않는다() {
    var analysis =
        new CharacterAnalysis(
            1,
            validGems(),
            new CharacterAnalysis.CardSummary(1, 0, 0),
            new CharacterAnalysis.EngravingSummary(0, 0, 5),
            new CharacterAnalysis.PassiveSummary(false, List.of()),
            new CharacterAnalysis.GridSummary(1, 2, 1, 1));
    var findings = CharacterDiagnostics.from(analysis).findings();
    assertThat(findings)
        .extracting(CharacterDiagnostics.Finding::code)
        .containsExactly(ARK_PASSIVE_DISABLED, ARK_GRID_GEM_INACTIVE, ARK_GRID_GEM_STATE_UNKNOWN);
    assertThat(findings)
        .extracting(CharacterDiagnostics.Finding::category)
        .containsExactly(SETTING_CHECK, SETTING_CHECK, DATA_GAP);
    assertThat(findings.getFirst().observedCount()).isNull();
    assertThat(findings.get(1).observedCount()).isEqualTo(1);
  }

  @Test
  void 낮은_레벨과_유효한_0을_임의의_성능_부족으로_판정하지_않는다() {
    var analysis =
        new CharacterAnalysis(
            1,
            validGems(),
            new CharacterAnalysis.CardSummary(1, 0, 0),
            new CharacterAnalysis.EngravingSummary(0, 0, 0),
            new CharacterAnalysis.PassiveSummary(
                true, List.of(new CharacterAnalysis.PointValue("진화", 0))),
            new CharacterAnalysis.GridSummary(1, 0, 0, 0));
    var report = CharacterDiagnostics.from(analysis);
    assertThat(report.findings()).isEmpty();
    assertThatThrownBy(() -> report.findings().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void 원본의_비정상_값은_집계와_진단에서_일관되게_제외한다() {
    var loadout =
        new CharacterLoadout(
            List.of(new CharacterLoadout.Equipment("무기", "무기", null, null)),
            new CharacterLoadout.Engravings(List.of(), List.of(), List.of()),
            new CharacterLoadout.Gems(
                List.of(new CharacterLoadout.Gem(0, null, null, -1, null)), null),
            new CharacterLoadout.Cards(
                List.of(new CharacterLoadout.Card(0, null, null, 6, 5, null)), List.of()),
            new CharacterLoadout.ArkPassive(
                null, true, List.of(new CharacterLoadout.PassivePoint("진화", -1, null)), List.of()),
            new CharacterLoadout.ArkGrid(
                List.of(new CharacterLoadout.GridSlot(0, null, null, null, null, List.of())),
                List.of()));
    var analysis = CharacterAnalysis.from(loadout);
    assertThat(analysis.gems().averageLevel()).isNull();
    assertThat(analysis.cards().knownAwakeningTotal()).isZero();
    assertThat(CharacterDiagnostics.from(analysis).findings())
        .extracting(CharacterDiagnostics.Finding::code)
        .containsExactly(GEM_LEVEL_UNKNOWN, CARD_AWAKENING_UNKNOWN, ARK_PASSIVE_POINT_UNKNOWN);
  }

  private CharacterAnalysis.GemSummary validGems() {
    return new CharacterAnalysis.GemSummary(
        1, 0, 1, 1, BigDecimal.ONE, List.of(new CharacterAnalysis.LevelCount(1, 1)));
  }
}
