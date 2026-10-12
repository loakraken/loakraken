package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 공식 응답을 해석한 도메인 형식(기준 캐릭터 10-09 검색 응답과 같은 문구)으로 만든 합성 데이터. 원문은 쓰지 않는다. */
class EquipmentSummaryTest {
  @Test
  void 계산_검증_필드를_도메인에서_뽑는다() {
    var summary = EquipmentSummary.from(profile("4035.27"), loadout(false));
    assertThat(summary.combatPower()).isEqualByComparingTo("4035.27");
    assertThat(summary.arkPassiveTitle()).isEqualTo("버스트");
    var f = summary.features();
    assertThat(f.attack()).isEqualTo(new EquipmentSummary.Attack(198702, 188163));
    assertThat(f.stats()).isEqualTo(new EquipmentSummary.Stats(576, 1816, 77, 75, 69, 71));
    assertThat(f.arkPassive().points()).isEqualTo(new EquipmentSummary.Trees(140, 101, 70));
    assertThat(f.arkPassive().karmaRank()).isEqualTo(new EquipmentSummary.Trees(6, 6, 6));
    assertThat(f.arkPassive().karmaLevel()).isEqualTo(new EquipmentSummary.Trees(21, 29, 27));
    assertThat(f.arkPassive().nodes())
        .containsExactly(
            new EquipmentSummary.Node("깨달음", 1, "버스트 강화", 1),
            new EquipmentSummary.Node("진화", 2, "예리한 감각", 2));
    assertThat(f.engravings())
        .containsExactly(
            new EquipmentSummary.Engraving("아드레날린", "유물", 3, 3),
            new EquipmentSummary.Engraving("안정된 상태", "유물", 4, 0));
    assertThat(f.gems())
        .containsExactly(
            new EquipmentSummary.Gem("광휘", 6, "DAMAGE"),
            new EquipmentSummary.Gem("광휘", 6, "COOLDOWN"),
            new EquipmentSummary.Gem("작열", 8, null));
    assertThat(f.gemBaseAttackPct()).isEqualByComparingTo("4.95");
    assertThat(f.weapon())
        .isEqualTo(new EquipmentSummary.Weapon(100, new BigDecimal("30"), 208130, 19));
    assertThat(f.armor()).extracting(EquipmentSummary.Armor::slot).containsExactly("투구", "상의");
    assertThat(f.accessories())
        .containsExactly(
            new EquipmentSummary.Accessory("목걸이", 87, List.of("추가 피해 +2.60%", "낙인력 +2.15%")),
            new EquipmentSummary.Accessory("반지", 76, List.of("치명타 적중률 +1.55%", "공격력 +80")),
            new EquipmentSummary.Accessory(
                "반지", 86, List.of("치명타 적중률 +1.55%", "상태이상 공격 지속시간 +0.20%")));
    assertThat(f.braceletLines())
        .containsExactly("특화 +81", "치명타 피해 +8.40%", "추가 피해가 2.5% 증가한다.", "악마 계열 피해량이 2.5% 증가한다.");
    assertThat(f.stoneLevelBonusBaseAttackPct()).isEqualByComparingTo("1.5");
    assertThat(f.orbParadisePower()).isEqualTo(35647915L);
    assertThat(f.arkGrid().effects()).containsEntry("추가 피해", new BigDecimal("4.12"));
    assertThat(f.arkGrid().cores())
        .containsExactly(
            new EquipmentSummary.Core(
                "질서",
                "해",
                "유물",
                18,
                List.of("[10P] 피해 +1%", "[14P] 피해 +2%", "[17P] 피해 +3%", "[18P] 피해 +4%")),
            new EquipmentSummary.Core("혼돈", "달", "고대", 17, List.of()));
    assertThat(f.cards())
        .isEqualTo(new EquipmentSummary.Cards("세상을 구하는 빛", 6, 30, new BigDecimal("15")));
    assertThat(f.skills()).containsExactly(new EquipmentSummary.Skill("윈드 컷", "BACK_ATTACK"));
  }

  @Test
  void 공식_순서만_바뀐_관측은_같은_요약이다() {
    assertThat(EquipmentSummary.from(profile("4035.27"), loadout(true)))
        .isEqualTo(EquipmentSummary.from(profile("4035.27"), loadout(false)));
  }

  @Test
  void 값이_없으면_null로_두고_추정하지_않는다() {
    var empty = new CharacterLoadout(List.of(), null, null, null, null, null, List.of());
    var summary =
        EquipmentSummary.from(
            new CharacterProfile(
                "모험가", "루페온", "바드", 70, null, null, null, null, null, null, List.of(), null),
            empty);
    var f = summary.features();
    assertThat(f.attack()).isNull();
    assertThat(f.stats().crit()).isNull();
    assertThat(f.arkPassive()).isNull();
    assertThat(f.engravings()).isNull();
    assertThat(f.gems()).isNull();
    assertThat(f.weapon()).isNull();
    assertThat(f.braceletLines()).isNull();
    assertThat(f.cards()).isNull();
    assertThat(f.orbParadisePower()).isNull();
    assertThat(f.armor()).isEmpty();
  }

  static CharacterProfile profile(String combatPower) {
    return new CharacterProfile(
        "기준캐릭터",
        "루페온",
        "블레이드",
        70,
        new BigDecimal("1770"),
        new BigDecimal(combatPower),
        300,
        null,
        null,
        null,
        List.of(
            stat("치명", "576"),
            stat("특화", "1816"),
            stat("제압", "75"),
            stat("신속", "77"),
            stat("인내", "69"),
            stat("숙련", "71"),
            new CharacterProfile.Stat(
                "공격력",
                "198702",
                List.of(
                    "적에게 주는 피해를 계산할 때 기준이 되는 값입니다.",
                    "힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 188163 입니다.",
                    "공격력 증감 효과로 공격력이 10539 증가되었습니다."))),
        null);
  }

  private static CharacterProfile.Stat stat(String type, String value) {
    return new CharacterProfile.Stat(type, value, List.of());
  }

  /** shuffled: 공식 응답 순서(장신구·보석·각인·노드·카드 효과)를 바꾼다. */
  static CharacterLoadout loadout(boolean shuffled) {
    var equipment =
        new ArrayList<>(
            List.of(
                equipment(
                    "무기",
                    detail(
                        19,
                        100,
                        List.of(
                            group("기본 효과", "무기 공격력 +208130"), group("추가 효과", "추가 피해 +30.00%")))),
                equipment("투구", detail(19, 95, List.of())),
                equipment("상의", detail(19, 97, List.of())),
                accessory("목걸이", 87, polish("추가 피해", "+2.60%"), polish("낙인력", "+2.15%")),
                accessory("반지", 86, polish("치명타 적중률", "+1.55%"), polish("상태이상 공격 지속시간", "+0.20%")),
                accessory("반지", 76, polish("치명타 적중률", "+1.55%"), polish("공격력", "+80")),
                equipment(
                    "어빌리티 스톤",
                    detail(
                        null,
                        null,
                        List.of(group("무작위 각인 효과", "[아드레날린] Lv.3", "[레벨 보너스] 기본 공격력 +1.50%")))),
                new Equipment(
                    "팔찌",
                    "팔찌",
                    null,
                    "고대",
                    new EquipmentDetail(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(
                            new BraceletEffect("특화 +81", null),
                            new BraceletEffect("치명타 피해 +8.40%", EffectGrade.MID),
                            new BraceletEffect(
                                "추가 피해가 2.5% 증가한다.\n악마 계열 피해량이 2.5% 증가한다.", EffectGrade.LOW)))),
                equipment(
                    "보주",
                    detail(
                        null,
                        null,
                        List.of(group("특수 효과", "[너른너울]", "시즌3 달성 최대 낙원력 : 35647915"))))));
    var gems =
        new ArrayList<>(
            List.of(
                new Gem(0, "6레벨 광휘의 보석 (귀속)", null, 6, "전설"),
                new Gem(1, "6레벨 광휘의 보석 (귀속)", null, 6, "전설"),
                new Gem(2, "8레벨 작열의 보석", null, 8, "전설")));
    var gemSkills =
        List.of(
            new GemSkill(0, "윈드 컷", List.of("피해 28.00% 증가"), "기본 공격력 0.45% 증가", null),
            new GemSkill(1, "서프라이즈 어택", List.of("재사용 대기시간 16.00% 감소"), null, null));
    var engravings =
        new ArrayList<>(
            List.of(
                new PassiveEngraving(null, "유물", 4, "안정된 상태", "…"),
                new PassiveEngraving(3, "유물", 3, "아드레날린", "…")));
    var nodes =
        new ArrayList<>(
            List.of(
                new PassiveEffect("진화", "진화 2티어 예리한 감각 Lv.2", null),
                new PassiveEffect("깨달음", "깨달음 1티어 버스트 강화 Lv.1", null)));
    var cardEffects =
        new ArrayList<>(
            List.of(
                new Effect("세상을 구하는 빛 2세트", "암속성 피해 감소 +10.00%"),
                new Effect("세상을 구하는 빛 6세트", "암속성 피해 감소 +10.00%"),
                new Effect("세상을 구하는 빛 6세트 (18각성합계)", "성속성 피해 +7.00%"),
                new Effect("세상을 구하는 빛 6세트 (24각성합계)", "성속성 피해 +4.00%"),
                new Effect("세상을 구하는 빛 6세트 (30각성합계)", "성속성 피해 +4.00%")));
    if (shuffled) {
      Collections.reverse(equipment);
      Collections.reverse(gems);
      Collections.reverse(engravings);
      Collections.reverse(nodes);
      Collections.reverse(cardEffects);
    }
    return new CharacterLoadout(
        equipment,
        new Engravings(List.of(), List.of(), engravings),
        new CharacterLoadout.Gems(gems, new GemEffects("기본 공격력 총합 : 4.95%", gemSkills)),
        new Cards(List.of(), List.of(new CardSet(0, List.of(0, 1, 2, 3, 4, 5), cardEffects))),
        new ArkPassive(
            "버스트",
            true,
            List.of(
                new PassivePoint("진화", 140, "6랭크 21레벨"),
                new PassivePoint("깨달음", 101, "6랭크 29레벨"),
                new PassivePoint("도약", 70, "6랭크 27레벨")),
            nodes),
        new ArkGrid(
            List.of(
                new GridSlot(
                    0,
                    null,
                    "질서의 해 코어 : 살상연희",
                    18,
                    "유물",
                    List.of(),
                    new CoreDetail(
                        "질서",
                        null,
                        List.of(
                            new CoreOption(10, "[10P] 피해 +1%"),
                            new CoreOption(14, "[14P] 피해 +2%"),
                            new CoreOption(17, "[17P] 피해 +3%"),
                            new CoreOption(18, "[18P] 피해 +4%"),
                            new CoreOption(19, "[19P] 피해 +5%")),
                        List.of())),
                new GridSlot(1, null, "혼돈의 달 코어 : 불타는 일격", 17, "고대", List.of())),
            List.of(
                new GridEffect("추가 피해", 51, "추가 피해 +4.12%", new BigDecimal("4.12")),
                new GridEffect("공격력", 28, "공격력 +1.02%", new BigDecimal("1.02")))),
        List.of(
            new Skill(
                "윈드 컷",
                null,
                14,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                AttackType.BACK_ATTACK),
            new Skill(
                "기본 스킬", null, 1, null, null, null, List.of(), null, List.of(), AttackType.NONE)));
  }

  private static Equipment equipment(String type, EquipmentDetail detail) {
    return new Equipment(type, type, null, "고대", detail);
  }

  private static Equipment accessory(String type, int quality, PolishEffect... polish) {
    return new Equipment(
        type,
        type,
        null,
        "고대",
        new EquipmentDetail(
            null, quality, null, 4, null, List.of(), List.of(), List.of(polish), List.of()));
  }

  private static PolishEffect polish(String name, String value) {
    return new PolishEffect(name, value, EffectGrade.HIGH);
  }

  private static EquipmentDetail detail(Integer refine, Integer quality, List<EffectGroup> groups) {
    return new EquipmentDetail(
        refine, quality, 1770, 4, null, groups, List.of(), List.of(), List.of());
  }

  private static EffectGroup group(String title, String... lines) {
    return new EffectGroup(title, List.of(lines));
  }
}
