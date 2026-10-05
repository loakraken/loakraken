package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterAnalysisTest {
  @Test
  void 미제공_섹션은_0이나_비활성으로_추정하지_않는다() {
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, null));
    assertThat(result.gems()).isNull();
    assertThat(result.cards()).isNull();
    assertThat(result.engravings()).isNull();
    assertThat(result.arkPassive()).isNull();
    assertThat(result.arkGrid()).isNull();
  }

  @Test
  void 빈_보석_목록의_평균과_범위는_null이다() {
    var summary = gems(List.of());
    assertThat(summary.count()).isZero();
    assertThat(summary.unknownLevelCount()).isZero();
    assertThat(summary.averageLevel()).isNull();
    assertThat(summary.minLevel()).isNull();
    assertThat(summary.maxLevel()).isNull();
    assertThat(summary.levelDistribution()).isEmpty();
  }

  @Test
  void 보석_분포는_정렬하고_평균에서_알_수_없는_레벨을_제외한다() {
    var summary = gems(List.of(gem(9), gem(7), gem(9), gem(null), gem(0), gem(-1)));
    assertThat(summary.count()).isEqualTo(6);
    assertThat(summary.unknownLevelCount()).isEqualTo(3);
    assertThat(summary.minLevel()).isEqualTo(7);
    assertThat(summary.maxLevel()).isEqualTo(9);
    assertThat(summary.averageLevel()).isEqualByComparingTo("8.33");
    assertThat(summary.levelDistribution())
        .containsExactly(
            new CharacterAnalysis.LevelCount(7, 1), new CharacterAnalysis.LevelCount(9, 2));
  }

  @Test
  void 모든_레벨이_알_수_없으면_평균_0을_만들지_않는다() {
    var summary = gems(List.of(gem(null), gem(-1)));
    assertThat(summary.count()).isEqualTo(2);
    assertThat(summary.unknownLevelCount()).isEqualTo(2);
    assertThat(summary.averageLevel()).isNull();
  }

  @Test
  void 카드_각성은_확인_가능한_값만_합산한다() {
    var cards =
        new CharacterLoadout.Cards(
            List.of(card(5, 5), card(0, 5), card(null, 5), card(-1, 5), card(6, 5)), List.of());
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, cards, null, null))
            .cards();
    assertThat(result.count()).isEqualTo(5);
    assertThat(result.unknownAwakeningCount()).isEqualTo(3);
    assertThat(result.knownAwakeningTotal()).isEqualTo(5);
  }

  @Test
  void 패시브_비활성과_포인트_0은_유효한_관측값이다() {
    var passive =
        new CharacterLoadout.ArkPassive(
            "패시브",
            false,
            List.of(
                new CharacterLoadout.PassivePoint("진화", 0, null),
                new CharacterLoadout.PassivePoint("깨달음", -1, null)),
            List.of());
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, passive, null))
            .arkPassive();
    assertThat(result.enabled()).isFalse();
    assertThat(result.points().getFirst().value()).isZero();
    assertThat(result.points().getLast().value()).isNull();
  }

  @Test
  void 그리드_활성_비활성_미확인_보석을_구분한다() {
    var slot =
        new CharacterLoadout.GridSlot(
            0, null, "코어", 14, "유물", List.of(gridGem(true), gridGem(false), gridGem(null)));
    var grid = new CharacterLoadout.ArkGrid(List.of(slot), List.of());
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, grid))
            .arkGrid();
    assertThat(result.coreCount()).isEqualTo(1);
    assertThat(result.activeGemCount()).isEqualTo(1);
    assertThat(result.inactiveGemCount()).isEqualTo(1);
    assertThat(result.unknownGemCount()).isEqualTo(1);
  }

  @Test
  void 장비_재련과_품질은_확인된_값만_집계하고_해석_불가_장비를_따로_센다() {
    var equipment =
        List.of(
            equipment(19, 100, new CharacterLoadout.PointBonus("진화", 24)),
            equipment(19, 95, new CharacterLoadout.PointBonus("진화", 24)),
            equipment(20, null, null),
            equipment(null, 87, new CharacterLoadout.PointBonus("깨달음", 13)),
            equipment(null, null, new CharacterLoadout.PointBonus("도약", 18)),
            new CharacterLoadout.Equipment("무기", "해석 불가", null, null, null));
    var result =
        CharacterAnalysis.from(new CharacterLoadout(equipment, null, null, null, null, null))
            .equipment();
    assertThat(result.detailCount()).isEqualTo(5);
    assertThat(result.detailUnavailableCount()).isEqualTo(1);
    assertThat(result.refineCount()).isEqualTo(3);
    assertThat(result.minRefineLevel()).isEqualTo(19);
    assertThat(result.maxRefineLevel()).isEqualTo(20);
    assertThat(result.refineDistribution())
        .containsExactly(
            new CharacterAnalysis.LevelCount(19, 2), new CharacterAnalysis.LevelCount(20, 1));
    assertThat(result.qualityCount()).isEqualTo(3);
    assertThat(result.minQuality()).isEqualTo(87);
    assertThat(result.maxQuality()).isEqualTo(100);
    assertThat(result.averageQuality()).isEqualByComparingTo("94.00");
    assertThat(result.arkPassivePoints())
        .containsExactly(
            new CharacterAnalysis.PointValue("진화", 48),
            new CharacterAnalysis.PointValue("깨달음", 13),
            new CharacterAnalysis.PointValue("도약", 18));
  }

  @Test
  void 장비가_없거나_값이_없으면_0으로_대체하지_않고_null로_둔다() {
    var empty =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, null))
            .equipment();
    assertThat(empty.detailCount()).isZero();
    assertThat(empty.minRefineLevel()).isNull();
    assertThat(empty.averageQuality()).isNull();
    assertThat(empty.refineDistribution()).isEmpty();
    var noValues =
        CharacterAnalysis.from(
                new CharacterLoadout(
                    List.of(equipment(null, null, null)), null, null, null, null, null))
            .equipment();
    assertThat(noValues.detailCount()).isEqualTo(1);
    assertThat(noValues.qualityCount()).isZero();
    assertThat(noValues.minQuality()).isNull();
    assertThat(noValues.arkPassivePoints()).isEmpty();
  }

  @Test
  void 품질_평균은_소수_둘째_자리에서_반올림한다() {
    var result =
        CharacterAnalysis.from(
                new CharacterLoadout(
                    List.of(
                        equipment(null, 100, null),
                        equipment(null, 95, null),
                        equipment(null, 95, null)),
                    null,
                    null,
                    null,
                    null,
                    null))
            .equipment();
    assertThat(result.averageQuality()).isEqualByComparingTo("96.67");
  }

  @Test
  void 품질은_무기_방어구와_장신구로_나눠_집계하고_그_외_장비는_그룹에서_제외한다() {
    var items =
        List.of(
            typed("무기", 100),
            typed("투구", 95),
            typed("상의", 95),
            typed("목걸이", 87),
            typed("반지", 76),
            typed("어빌리티 스톤", 50));
    var groups =
        CharacterAnalysis.from(new CharacterLoadout(items, null, null, null, null, null))
            .equipment()
            .qualityGroups();
    assertThat(groups)
        .extracting(CharacterAnalysis.QualityGroup::group)
        .containsExactly(CharacterAnalysis.WEAPON_ARMOR, CharacterAnalysis.ACCESSORY);
    assertThat(groups.get(0).count()).isEqualTo(3);
    assertThat(groups.get(0).min()).isEqualTo(95);
    assertThat(groups.get(0).average()).isEqualByComparingTo("96.67");
    assertThat(groups.get(1).count()).isEqualTo(2);
    assertThat(groups.get(1).max()).isEqualTo(87);
    assertThat(groups.get(1).average()).isEqualByComparingTo("81.50");
    var empty =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, null))
            .equipment()
            .qualityGroups();
    assertThat(empty).allMatch(group -> group.count() == 0 && group.average() == null);
  }

  @Test
  void 보석_효과는_피해와_재사용_대기시간_형식만_분류하고_나머지는_미확인으로_센다() {
    var skills =
        List.of(
            skill("피해 28.00% 증가"),
            skill("재사용 대기시간 16.00% 감소"),
            skill("재사용 대기시간 16.00% 감소"),
            skill("피해 증가"),
            new CharacterLoadout.GemSkill(5, "스킬", List.of(), null, null));
    var result =
        gemsWith(
            new CharacterLoadout.Gems(
                List.of(gem(6)), new CharacterLoadout.GemEffects(null, skills)));
    assertThat(result.damageEffectCount()).isEqualTo(1);
    assertThat(result.cooldownEffectCount()).isEqualTo(2);
    assertThat(result.unknownEffectCount()).isEqualTo(2);
    var noEffects = gemsWith(new CharacterLoadout.Gems(List.of(gem(6)), null));
    assertThat(noEffects.damageEffectCount() + noEffects.cooldownEffectCount()).isZero();
  }

  @Test
  void 아크그리드는_질서와_혼돈_코어의_포인트를_따로_합산한다() {
    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(
                slot("질서의 해 코어 : 살상연희", 18),
                slot("질서의 달 코어 : 쌍검난무", 17),
                slot("혼돈의 별 코어 : 공격", 17),
                slot("혼돈의 달 코어", null),
                slot("기타 코어", 5)),
            List.of());
    var result =
        CharacterAnalysis.from(new CharacterLoadout(List.of(), null, null, null, null, grid))
            .arkGrid();
    assertThat(result.orderPoints()).isEqualTo(35);
    assertThat(result.chaosPoints()).isEqualTo(17);
    var none =
        CharacterAnalysis.from(
                new CharacterLoadout(
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    new CharacterLoadout.ArkGrid(List.of(), List.of())))
            .arkGrid();
    assertThat(none.orderPoints()).isNull();
    assertThat(none.chaosPoints()).isNull();
  }

  private CharacterLoadout.Equipment typed(String type, Integer quality) {
    return new CharacterLoadout.Equipment(
        type,
        type,
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            null, quality, null, 4, null, List.of(), List.of(), List.of(), List.of()));
  }

  private CharacterLoadout.GemSkill skill(String description) {
    return new CharacterLoadout.GemSkill(0, "스킬", List.of(description), null, null);
  }

  private CharacterAnalysis.GemSummary gemsWith(CharacterLoadout.Gems gems) {
    return CharacterAnalysis.from(new CharacterLoadout(List.of(), null, gems, null, null, null))
        .gems();
  }

  private CharacterLoadout.GridSlot slot(String name, Integer point) {
    return new CharacterLoadout.GridSlot(0, null, name, point, null, List.of());
  }

  private CharacterLoadout.Equipment equipment(
      Integer refineLevel, Integer quality, CharacterLoadout.PointBonus point) {
    return new CharacterLoadout.Equipment(
        "장비",
        "장비",
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            refineLevel, quality, null, 4, point, List.of(), List.of(), List.of(), List.of()));
  }

  private CharacterAnalysis.GemSummary gems(List<CharacterLoadout.Gem> gems) {
    return CharacterAnalysis.from(
            new CharacterLoadout(
                List.of(), null, new CharacterLoadout.Gems(gems, null), null, null, null))
        .gems();
  }

  private CharacterLoadout.Gem gem(Integer level) {
    return new CharacterLoadout.Gem(0, "보석", null, level, "전설");
  }

  private CharacterLoadout.Card card(Integer count, Integer total) {
    return new CharacterLoadout.Card(0, "카드", null, count, total, "전설");
  }

  private CharacterLoadout.GridGem gridGem(Boolean active) {
    return new CharacterLoadout.GridGem(0, null, active, "희귀");
  }
}
