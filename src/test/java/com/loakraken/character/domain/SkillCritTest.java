package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout.AttackType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 스킬별 치명타 적중률(#168). 트라이포드 문구는 공식 armory(10-09 리퍼 표본) 형식이다. */
class SkillCritTest {
  private static final BigDecimal OBSERVED = new BigDecimal("40.00");

  @Test
  void 관측_치적에_백_어택과_선택_트라이포드를_더한다() {
    var limitations = new ArrayList<String>();
    var skills =
        CritSpeedCalculation.skillCrits(
            loadout(
                skill(
                    "라스트 그래피티",
                    AttackType.BACK_ATTACK,
                    tripod(
                        "유언", "더욱 높이 뛰어올라 강하게 내려찍어 치명타 적중률이 20.0% 증가하며, 기본 피해량의 150.0% 피해를 준다.")),
                skill(
                    "사일런트 스매셔",
                    AttackType.HEAD_ATTACK,
                    tripod(
                        "지면 강타",
                        "[암] 속성으로 변경되고, 리퍼가 더욱 강하게 하강하여 치명타 적중률이 20.0%, 적에게 주는 피해가 60.0% 증가한다.")),
                skill("나이트메어", AttackType.NONE, tripod("빠른 준비", "재사용 대기시간이 5.0초 감소한다."))),
            OBSERVED,
            new BigDecimal("100"),
            limitations);
    assertThat(skills)
        .extracting(CritSpeedCalculation.SkillCrit::name)
        .containsExactly("나이트메어", "라스트 그래피티", "사일런트 스매셔");
    var graffiti = skills.get(1);
    assertThat(graffiti.backAttack()).isEqualByComparingTo("10");
    assertThat(graffiti.tripod()).isEqualByComparingTo("20");
    assertThat(graffiti.total()).isEqualByComparingTo("70");
    assertThat(graffiti.tripods())
        .extracting(CritSpeedCalculation.SkillTripod::name)
        .containsExactly("유언");
    var smasher = skills.get(2);
    assertThat(smasher.backAttack()).isZero();
    assertThat(smasher.total()).isEqualByComparingTo("60");
    assertThat(skills.get(0).total()).isEqualByComparingTo("40");
    assertThat(limitations).isEmpty();
  }

  @Test
  void 조건부_트라이포드는_더하지_않고_따로_둔다() {
    var skills =
        CritSpeedCalculation.skillCrits(
            loadout(
                skill(
                    "차징 샷",
                    AttackType.NONE,
                    tripod("오버차지", "오버차지 시 치명타 적중률이 30.0% 증가한다."),
                    tripod("폭발 화살", "폭발의 치명타 적중률이 15.0% 증가한다."),
                    tripod("재정비", "다시 사용할 수 있게 되며 치명타 적중률이 10.0% 증가한다."))),
            OBSERVED,
            new BigDecimal("100"),
            new ArrayList<>());
    var shot = skills.getFirst();
    assertThat(shot.tripod()).isEqualByComparingTo("10");
    assertThat(shot.conditional())
        .extracting(CritSpeedCalculation.SkillTripod::name)
        .containsExactly("오버차지", "폭발 화살");
    assertThat(shot.conditional().getFirst().text()).startsWith("오버차지 시");
  }

  @Test
  void 피격이상_면역인_적과_차지_완료는_기본으로_더하고_화면_값은_상한이다() {
    // crit-speed-v7(PM-백엔드 10-11 21:57). 공식 트라이포드 문구(10-12 조회), 계산 검증 표본 c04: 유성강천·굉열파 115.94 →
    // 100.
    var observed = new BigDecimal("15.94");
    var toggles = new ArrayList<CritSpeedCalculation.ToggleState>();
    var skills =
        CritSpeedCalculation.skillCrits(
            loadout(
                skill(
                    "유성강천",
                    AttackType.NONE,
                    tripod(
                        "강력한 마무리",
                        "더욱 빠르게 뛰고 강하게 내려찍어 피격이상 면역인 적에게 공격 적중 시 치명타 적중률이 100.0% 증가하며 치명타 피해가 60.0% 증가한다.")),
                skill(
                    "굉열파",
                    AttackType.NONE,
                    tripod(
                        "공파섬",
                        "차지 조작으로 변경되며, 차지 완료 시 적에게 주는 피해가 83.0% 증가하고 치명타 적중률이 40.0% 증가한다."))),
            observed,
            new BigDecimal("100"),
            BigDecimal.valueOf(200),
            new ArrayList<>(),
            List.of(),
            CritSpeedCalculation.Assumptions.NONE,
            toggles);
    var finish = skills.get(1);
    assertThat(finish.name()).isEqualTo("유성강천");
    assertThat(finish.total()).isEqualByComparingTo("115.94");
    assertThat(finish.effective()).isEqualByComparingTo("100");
    assertThat(finish.conditional()).isEmpty();
    var charge = skills.get(0);
    // 표본의 115.94는 다른 트라이포드(+60)까지 더한 값이다. 여기서는 공파섬 +40만 확인한다.
    assertThat(charge.total()).isEqualByComparingTo("55.94");
    assertThat(charge.tripods())
        .extracting(CritSpeedCalculation.SkillTripod::name)
        .containsExactly("공파섬");
    assertThat(toggles)
        .extracting(CritSpeedCalculation.ToggleState::key)
        .containsExactly("피격이상 면역인 적", "차지 완료");
    assertThat(toggles).allMatch(CritSpeedCalculation.ToggleState::on);
    assertThat(toggles).allMatch(CritSpeedCalculation.ToggleState::coreState);

    // 끄면 예전처럼 조건부로 따로 둔다.
    var off =
        new CritSpeedCalculation.Assumptions(
            false,
            false,
            0,
            null,
            null,
            null,
            null,
            null,
            true,
            java.util.Map.of("피격이상 면역인 적", false));
    var offSkills =
        CritSpeedCalculation.skillCrits(
            loadout(
                skill(
                    "유성강천",
                    AttackType.NONE,
                    tripod(
                        "강력한 마무리",
                        "더욱 빠르게 뛰고 강하게 내려찍어 피격이상 면역인 적에게 공격 적중 시 치명타 적중률이 100.0% 증가하며 치명타 피해가 60.0% 증가한다."))),
            observed,
            new BigDecimal("100"),
            BigDecimal.valueOf(200),
            new ArrayList<>(),
            List.of(),
            off,
            new ArrayList<>());
    assertThat(offSkills.getFirst().total()).isEqualByComparingTo("15.94");
    assertThat(offSkills.getFirst().conditional())
        .extracting(CritSpeedCalculation.SkillTripod::name)
        .containsExactly("강력한 마무리");
  }

  @Test
  void 상한을_적용하고_공격_타입이나_트라이포드_문구를_모르면_null이다() {
    var limitations = new ArrayList<String>();
    var skills =
        CritSpeedCalculation.skillCrits(
            loadout(
                skill("가", AttackType.BACK_ATTACK, tripod("강화", "치명타 적중률이 40.0% 증가한다.")),
                skill("나", null, tripod("강화", "치명타 적중률이 10.0% 증가한다.")),
                skill("다", AttackType.NONE, new CharacterLoadout.Tripod(1, 1, "옛 캐시", null))),
            OBSERVED,
            new BigDecimal("80"),
            limitations);
    assertThat(skills.get(0).total()).isEqualByComparingTo("90");
    assertThat(skills.get(0).effective()).isEqualByComparingTo("80");
    assertThat(skills.get(1).backAttack()).isNull();
    assertThat(skills.get(1).total()).isNull();
    assertThat(skills.get(2).tripod()).isNull();
    assertThat(skills.get(2).effective()).isNull();
    assertThat(limitations).singleElement().asString().contains("갱신 전 캐시");
  }

  private static CharacterLoadout loadout(CharacterLoadout.Skill... skills) {
    return new CharacterLoadout(List.of(), null, null, null, null, null, List.of(skills));
  }

  private static CharacterLoadout.Skill skill(
      String name, AttackType attackType, CharacterLoadout.Tripod... tripods) {
    return new CharacterLoadout.Skill(
        name, null, 12, null, null, null, List.of(tripods), null, List.of(), attackType);
  }

  private static CharacterLoadout.Tripod tripod(String name, String effect) {
    return new CharacterLoadout.Tripod(1, 1, name, null, effect);
  }
}
