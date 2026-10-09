package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CritSpeedCalculation.Assumptions;
import com.loakraken.character.domain.CritSpeedCalculation.Axis;
import com.loakraken.character.domain.CritSpeedCalculation.Category;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 직업별 치명 효과 정답표(#189). 노드 문구는 2026-10-09 공식 armory 표본(정답표 표본 캐릭터)의 형식이다. 판정은 계산 검증 [[직업별 치명 효과
 * 정답표]]를 따른다.
 */
class CritEffectTableTest {
  private static final String MADNESS =
      "일반 상태에서 분노 게이지가 항상 최대치로 유지되며, 폭주 시 어둠의 힘을 이용한 광기 상태가 된다. 광기 상태에서는 공격속도 및 이동속도가 15.0%, 치명타 적중률이 30.0% 증가하며 분노 게이지가 자연소모되지 않는다.";

  @Test
  void 일치_포함_노드는_조건_문구와_관계없이_더하고_갈림은_빼며_표에_없는_노드는_합산하지_않는다() {
    var result =
        calculate(
            "버서커",
            List.of(
                node("깨달음 1티어 광기 Lv.3", MADNESS),
                node("깨달음 2티어 분노 자극 Lv.1", "폭주 및 광기 상태에서 치명타 피해량이 3.0% 증가한다."),
                node("깨달음 1티어 혼돈 강화 Lv.3", "악마화 중 치명타 적중률이 30.0% 증가한다."),
                node("도약 1티어 관통 필살 Lv.3", "적룡필살의 돌진 공격 거리가 4.0m 증가하고, 치명타 적중률이 100.0% 증가한다."),
                node("깨달음 2티어 격노의 악장 Lv.2", "적에게 주는 치명타 피해가 8.0% 증가한다.")),
            List.of(),
            null,
            Assumptions.NONE);
    assertThat(values(result, Axis.CRIT, Category.ARK_PASSIVE)).containsExactly("광기 Lv.3=30");
    assertThat(values(result, Axis.CRIT_DAMAGE, Category.ARK_PASSIVE))
        .containsExactly("분노 자극 Lv.1=3");
    assertThat(result.limitations())
        .anyMatch(line -> line.contains("혼돈 강화 Lv.3 30%(로펙은 포함)"))
        .anyMatch(line -> line.contains("관통 필살 Lv.3 100%(적룡필살에만)"))
        .anyMatch(line -> line.contains("격노의 악장 Lv.2 8%"));
  }

  private static final String FOOTSTEPS =
      "혼돈 상태일 때 피니쉬 스텝의 적에게 주는 피해가 28.0% 증가하고 백어택 적중 시 추가로 치명타 적중률이 20.0% 증가한다.";

  @Test
  void 스킬_한정_노드는_기본에서_빼고_그_스킬의_스킬별_치적에만_더한다() {
    var nodes =
        List.of(
            node("도약 2티어 관통 필살 Lv.3", "적룡필살의 돌진 공격 거리가 4.0m 증가하고, 치명타 적중률이 100.0% 증가한다."),
            node("깨달음 2티어 암살자의 발자취 Lv.3", FOOTSTEPS));
    var skills =
        List.of(
            attack("적룡필살", CharacterLoadout.AttackType.NONE),
            attack("쉐도우 닷", CharacterLoadout.AttackType.BACK_ATTACK),
            attack("디스토션", CharacterLoadout.AttackType.NONE));
    var on = calculate("리퍼", nodes, skills, null, Assumptions.NONE);
    var off =
        calculate(
            "리퍼",
            nodes,
            skills,
            null,
            new Assumptions(false, false, 0, null, null, null, null, null, false));
    // 기본 치적에는 들어가지 않는다.
    assertThat(values(on, Axis.CRIT, Category.ARK_PASSIVE)).isEmpty();
    assertThat(on.crit().totalRate()).isEqualByComparingTo(off.crit().totalRate());
    var base = on.crit().observedRate();
    assertThat(skill(on, "적룡필살").total()).isEqualByComparingTo(base.add(new BigDecimal("100")));
    // 암살자의 발자취는 백 어택 스킬에만, 직업 상태 가정과 함께 켜고 끈다.
    assertThat(skill(on, "쉐도우 닷").total()).isEqualByComparingTo(base.add(new BigDecimal("30")));
    assertThat(skill(on, "쉐도우 닷").nodes())
        .extracting(CritSpeedCalculation.SkillTripod::name)
        .containsExactly("암살자의 발자취 Lv.3");
    assertThat(skill(off, "쉐도우 닷").total()).isEqualByComparingTo(base.add(BigDecimal.TEN));
    assertThat(skill(on, "디스토션").total()).isEqualByComparingTo(base);
    assertThat(on.limitations())
        .anyMatch(line -> line.contains("암살자의 발자취 Lv.3 20%(백 어택 스킬에만)"))
        .noneMatch(line -> line.contains("분류 중"));
  }

  private static CritSpeedCalculation.SkillCrit skill(
      CritSpeedCalculation.Result result, String name) {
    return result.skills().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
  }

  private static CharacterLoadout.Skill attack(String name, CharacterLoadout.AttackType type) {
    return new CharacterLoadout.Skill(
        name, null, 12, null, null, null, List.of(), null, List.of(), type);
  }

  @Test
  void 노드_문구에_없는_직업_버프는_직업_스킬_트라이포드로_더하고_끄면_뺀다() {
    var skills = List.of(skill("용맹의 포효", "급소 노출", "빠른 준비"), skill("뇌명각", "급소 노출", "섬뢰"));
    var on = calculate("배틀마스터", List.of(), skills, null, Assumptions.NONE);
    assertThat(values(on, Axis.CRIT, Category.ASSUMPTION))
        .containsExactly("용맹의 포효 버프 유지 가정=20", "급소 노출 시너지(자신)=10");
    var slayer = calculate("슬레이어", List.of(), List.of(), null, Assumptions.NONE);
    assertThat(values(slayer, Axis.CRIT, Category.ASSUMPTION)).containsExactly("폭주 상태 가정=30");
    var off =
        calculate(
            "배틀마스터",
            List.of(),
            skills,
            null,
            new Assumptions(false, false, 0, null, null, null, null, null, false));
    assertThat(values(off, Axis.CRIT, Category.ASSUMPTION)).isEmpty();
    // 레벨 1(투자 안 함) 스킬은 장착으로 보지 않는다.
    var unused =
        calculate(
            "스트라이커",
            List.of(),
            List.of(
                new CharacterLoadout.Skill(
                    "번개의 속삭임", null, 1, null, null, null, List.of(), null, List.of(), null)),
            null,
            Assumptions.NONE);
    assertThat(values(unused, Axis.CRIT, Category.ASSUMPTION)).isEmpty();
  }

  @Test
  void 공용_코어는_달성한_구간의_치명_문구를_더한다() {
    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(
                core(
                    "혼돈의 해 코어 : 재빠른 공격",
                    20,
                    List.of(
                        new CharacterLoadout.CoreOption(10, "공격 속도가 1.00% 증가한다."),
                        new CharacterLoadout.CoreOption(14, "치명타 피해가 1.40% 증가한다."),
                        new CharacterLoadout.CoreOption(
                            17, "공격 속도가 3.00% 증가하고, 치명타 피해가 5.60% 증가한다."),
                        new CharacterLoadout.CoreOption(18, "치명타 피해가 0.45% 증가한다."),
                        new CharacterLoadout.CoreOption(19, "치명타 피해가 0.45% 증가한다."),
                        new CharacterLoadout.CoreOption(20, "치명타 피해가 0.45% 증가한다."))),
                core(
                    "혼돈의 달 코어 : 부수는 일격",
                    19,
                    List.of(
                        new CharacterLoadout.CoreOption(14, "치명타 적중률이 0.65% 증가한다."),
                        new CharacterLoadout.CoreOption(
                            17, "치명타 적중률이 2.60% 증가하며, 부위 파괴 공격의 재발동 대기시간이 15.0 초 감소한다."),
                        new CharacterLoadout.CoreOption(18, "치명타 적중률이 0.21% 증가한다."),
                        new CharacterLoadout.CoreOption(19, "치명타 적중률이 0.21% 증가한다."),
                        new CharacterLoadout.CoreOption(20, "치명타 적중률이 0.21% 증가한다.")))),
            List.of());
    var result = calculate("블레이드", List.of(), List.of(), grid, Assumptions.NONE);
    assertThat(values(result, Axis.CRIT_DAMAGE, Category.ARK_GRID))
        .containsExactly("혼돈의 해 코어 : 재빠른 공격 20P=8.35");
    assertThat(values(result, Axis.CRIT, Category.ARK_GRID))
        .containsExactly("혼돈의 달 코어 : 부수는 일격 19P=3.67");
  }

  @Test
  void 기민함은_상한을_적용한_이동_공격_속도에_비율을_곱한다() {
    var result =
        calculate(
            "기상술사",
            List.of(
                node(
                    "깨달음 1티어 기민함 Lv.3",
                    "기본 공격 속도 증가량 %의 120.0% 만큼 치명타 피해량이 증가하고 기본 이동 속도 증가량 %의 30.0% 만큼 치명타 적중률이 증가한다.")),
            List.of(),
            null,
            new Assumptions(
                false, false, 0, null, null, null, new BigDecimal("50"), new BigDecimal("50")));
    // 신속 1.32 + 가정 50 → 상한 40
    assertThat(values(result, Axis.CRIT, Category.ARK_PASSIVE))
        .containsExactly("기민함(이동 속도 40%)=12");
    assertThat(values(result, Axis.CRIT_DAMAGE, Category.ARK_PASSIVE))
        .containsExactly("기민함(공격 속도 40%)=48");
  }

  /** 츄델(질풍노도·질량 증가, 2026-10-09): 신속 29.25 + 질풍노도 12 - 질량 증가 10. 두 사이트는 감소를 빼지 않고 48로 본다. */
  @Test
  void 기민함의_속도_증가량은_감소_효과를_빼지_않는다() {
    var engravings =
        new CharacterLoadout.Engravings(
            List.of(),
            List.of(),
            List.of(
                new CharacterLoadout.PassiveEngraving(
                    null, "유물", 4, "질량 증가", "공격속도가 10.00% 감소하지만, 적에게 주는 피해가 22.75% 증가한다.")));
    var result =
        calculate(
            "기상술사",
            List.of(
                node(
                    "깨달음 1티어 기민함 Lv.3",
                    "기본 공격 속도 증가량 %의 120.0% 만큼 치명타 피해량이 증가하고 기본 이동 속도 증가량 %의 30.0% 만큼 치명타 적중률이 증가한다.")),
            List.of(),
            null,
            engravings,
            new Assumptions(false, false, 0, null, null, null, new BigDecimal("39.93"), null));
    // 공격 속도 증가분: 신속 1.32 + 가정 39.93 = 41.25 → 상한 40 (질량 증가 -10은 빼지 않음)
    assertThat(values(result, Axis.CRIT_DAMAGE, Category.ARK_PASSIVE))
        .containsExactly("기민함(공격 속도 40%)=48");
  }

  @Test
  void 표의_노드_이름은_레벨과_관계없이_찾는다() {
    assertThat(CritEffectTable.node("광기 Lv.3").rate()).isEqualTo(CritEffectTable.Decision.INCLUDE);
    assertThat(CritEffectTable.node("곡예사 Lv.1").damage()).isEqualTo(CritEffectTable.Decision.SPLIT);
    assertThat(CritEffectTable.node("없는 노드 Lv.1")).isNull();
  }

  private static List<String> values(
      CritSpeedCalculation.Result result, Axis axis, Category category) {
    return result.sources().stream()
        .filter(source -> source.axis() == axis && source.category() == category)
        .map(source -> source.name() + "=" + source.value().stripTrailingZeros().toPlainString())
        .toList();
  }

  private static CritSpeedCalculation.Result calculate(
      String className,
      List<CharacterLoadout.PassiveEffect> nodes,
      List<CharacterLoadout.Skill> skills,
      CharacterLoadout.ArkGrid grid,
      Assumptions assumptions) {
    return calculate(className, nodes, skills, grid, null, assumptions);
  }

  private static CritSpeedCalculation.Result calculate(
      String className,
      List<CharacterLoadout.PassiveEffect> nodes,
      List<CharacterLoadout.Skill> skills,
      CharacterLoadout.ArkGrid grid,
      CharacterLoadout.Engravings engravings,
      Assumptions assumptions) {
    var profile =
        new CharacterProfile(
            "표본",
            "카단",
            className,
            70,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(
                new CharacterProfile.Stat("치명", "576", List.of("치명타 적중률이 20.61% 증가합니다.")),
                new CharacterProfile.Stat(
                    "신속", "77", List.of("공격 속도가 1.32% 증가합니다.", "이동 속도가 1.32% 증가합니다."))));
    var loadout =
        new CharacterLoadout(
            List.of(),
            engravings,
            null,
            null,
            new CharacterLoadout.ArkPassive("각인", true, List.of(), nodes),
            grid,
            skills);
    return CritSpeedCalculation.calculate(profile, loadout, assumptions);
  }

  private static CharacterLoadout.PassiveEffect node(String description, String effect) {
    return new CharacterLoadout.PassiveEffect("깨달음", description, null, effect);
  }

  private static CharacterLoadout.Skill skill(String name, String... tripods) {
    return new CharacterLoadout.Skill(
        name,
        null,
        12,
        null,
        null,
        null,
        java.util.Arrays.stream(tripods)
            .map(t -> new CharacterLoadout.Tripod(1, 1, t, null))
            .toList(),
        null,
        List.of(),
        null);
  }

  private static CharacterLoadout.GridSlot core(
      String name, int point, List<CharacterLoadout.CoreOption> options) {
    return new CharacterLoadout.GridSlot(
        0,
        null,
        name,
        point,
        "유물",
        List.of(),
        new CharacterLoadout.CoreDetail("혼돈", null, options, List.of()));
  }
}
