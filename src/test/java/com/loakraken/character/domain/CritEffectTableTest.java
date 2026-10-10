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
  void 일치_포함_노드는_조건_문구와_관계없이_더하고_핵심_상태는_기본_켬_토글로_더하며_스킬_한정은_빼고_표에_없는_노드는_합산하지_않는다() {
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
        .containsExactly("분노 자극 Lv.1=3", "격노의 악장 Lv.2=8");
    // 데모닉 혼돈 강화 "악마화 중"은 핵심 상태라 기본 켬 토글(PM 총괄 23:24)
    assertThat(values(result, Axis.CRIT, Category.ASSUMPTION))
        .containsExactly("악마화 상태 가정(혼돈 강화 Lv.3)=30");
    assertThat(result.toggles())
        .extracting(t -> t.key() + "/" + t.axis() + "/" + t.coreState() + "/" + t.on())
        .containsExactlyInAnyOrder(
            "혼돈 강화/CRIT/true/true",
            // 광기 "공격속도 및 이동속도가 15.0%"는 핵심 상태 공이속 토글(#198)
            "광기/ATTACK_SPEED/true/true",
            "광기/MOVE_SPEED/true/true");
    assertThat(result.limitations())
        .anyMatch(line -> line.contains("관통 필살 Lv.3 100%(적룡필살에만)"))
        .noneMatch(line -> line.contains("로펙"));
  }

  /** 계산 검증 10-10 01:55 환업·데니멀즈·소녀양점순 공식 코어 문구. */
  @Test
  void 직업_코어는_이름_표로_판정하고_표에_없는_치명_문구는_분류_중으로_남긴다() {
    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(
                core(
                    "질서의 별 코어 : 초극뇌연격",
                    20,
                    List.of(
                        new CharacterLoadout.CoreOption(10, "오의 : 뇌호격의 피해량이 3.0% 증가한다."),
                        new CharacterLoadout.CoreOption(18, "치명타 피해량이 0.52% 증가한다."),
                        new CharacterLoadout.CoreOption(19, "치명타 피해량이 0.52% 증가한다."),
                        new CharacterLoadout.CoreOption(20, "치명타 피해량이 0.52% 증가한다."))),
                core(
                    "질서의 해 코어 : 샷건 오버로드",
                    20,
                    List.of(
                        new CharacterLoadout.CoreOption(
                            14,
                            "샷건 스킬이 적에게 적중 시 급소 노출 효과가 적용되어, 대상이 자신 및 파티원에게 받는 치명타 저항률이 5.0초 동안 10.0% 감소한다."))),
                core(
                    "질서의 해 코어 : 천둥",
                    19,
                    List.of(
                        new CharacterLoadout.CoreOption(
                            17,
                            "'운명: 천둥'을 소모하면 20.0초 간 '운명: 천둥의 가호' 효과를 획득한다. '운명: 천둥의 가호' : 자신의 치명타 적중률이 6.0% 증가하고, 이동 속도가 10.0% 증가한다."))),
                core(
                    "질서의 달 코어 : 표에 없는 코어",
                    20,
                    List.of(new CharacterLoadout.CoreOption(14, "치명타 적중률이 5.0% 증가한다.")))),
            List.of());
    var result = calculate("데빌헌터", List.of(), List.of(), grid, Assumptions.NONE);
    assertThat(values(result, Axis.CRIT_DAMAGE, Category.ARK_GRID))
        .containsExactly("질서의 별 코어 : 초극뇌연격 20P=1.56");
    assertThat(values(result, Axis.CRIT, Category.ARK_GRID)).isEmpty();
    // 샷건 오버로드의 급소 노출은 자기 시너지 10(트라이포드 없이도) — 스킬 버프 가정이라 기본 끔(10-10 18:26)
    assertThat(values(result, Axis.CRIT, Category.ASSUMPTION)).isEmpty();
    assertThat(result.toggles())
        .extracting(
            t -> t.key() + "=" + t.value().stripTrailingZeros().toPlainString() + "/" + t.on())
        .contains("급소 노출 시너지(자신)=10/false");
    assertThat(result.toggles())
        .extracting(
            t -> t.key() + "=" + t.value().stripTrailingZeros().toPlainString() + "/" + t.on())
        .contains("천둥=6/false");
    assertThat(result.limitations())
        .anyMatch(line -> line.contains("분류 중") && line.contains("질서의 달 코어 : 표에 없는 코어"));
  }

  /** #198 재대조(계산 검증 10-10 02:10) 만배·츄델·한국남자·스카우터 공식 문구. */
  @Test
  void 공이속_직업_버프_공용_코어_트라이포드와_자신의_이동속도를_읽는다() {
    var slayer = calculate("슬레이어", List.of(), List.of(), null, Assumptions.NONE);
    // 신속 1.32 + 폭주 20(치명 폭주와 같은 토글)
    assertThat(slayer.speed().attack().total()).isEqualByComparingTo("21.32");
    assertThat(slayer.toggles())
        .extracting(t -> t.key() + "/" + t.axis())
        .contains("폭주 상태 가정/CRIT", "폭주 상태 가정/ATTACK_SPEED", "폭주 상태 가정/MOVE_SPEED");

    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(
                core(
                    "혼돈의 해 코어 : 재빠른 공격",
                    20,
                    List.of(
                        new CharacterLoadout.CoreOption(10, "공격 속도가 1.00% 증가한다."),
                        new CharacterLoadout.CoreOption(
                            17, "공격 속도가 3.00% 증가하고, 치명타 피해가 5.60% 증가한다.")))),
            List.of());
    var core = calculate("블레이드", List.of(), List.of(), grid, Assumptions.NONE);
    assertThat(values(core, Axis.ATTACK_SPEED, Category.ARK_GRID))
        .containsExactly("혼돈의 해 코어 : 재빠른 공격 20P=4");

    var nimble =
        new CharacterLoadout.Skill(
            "기동 타격",
            null,
            12,
            null,
            null,
            null,
            List.of(
                new CharacterLoadout.Tripod(
                    1, 2, "날렵함", null, "구르기 이후 8.0초 동안 공격 속도와 이동 속도가 19.2% 증가한다."),
                new CharacterLoadout.Tripod(2, 1, "표에 없는 트라이포드", null, "공격 속도가 5.0% 증가한다.")),
            null,
            List.of(),
            CharacterLoadout.AttackType.NONE);
    var drone =
        node(
            "깨달음 1티어 아르데타인의 기술 Lv.3",
            "배터리 최대량이 20.0% 증가하고 진화된 드론 전투 기술을 적용하여 드론의 이동속도가 증가한다. 또한 드론이 부착된 상태에서 자신의 이동속도가 10.0% 증가한다.");
    var gunner = calculate("스카우터", List.of(drone), List.of(nimble), null, Assumptions.NONE);
    // 날렵함은 트라이포드 버프 가정이라 기본 끔(10-10 18:26) → 신속 1.32만
    assertThat(gunner.speed().attack().total()).isEqualByComparingTo("1.32");
    // 이동 속도: 신속 1.32 + 드론 부착 10(자신의 이동속도, 핵심 상태라 기본 켬)
    assertThat(gunner.speed().move().total()).isEqualByComparingTo("11.32");
    assertThat(gunner.toggles())
        .extracting(t -> t.key() + "/" + t.axis() + "/" + t.on())
        .contains("날렵함/ATTACK_SPEED/false", "아르데타인의 기술/MOVE_SPEED/true");
    assertThat(gunner.limitations()).anyMatch(line -> line.contains("기동 타격 - 표에 없는 트라이포드(분류 중)"));
  }

  /** #203 재대조(10-10): 속공 준비 "공격 속도가 4.0초 간 30.0%"는 토글로 읽고, 고독한 기사 이속 −10은 두 사이트 제외라 기본 끔. */
  @Test
  void 지속_시간이_낀_공이속_문구를_읽고_고독한_기사는_기본_끔이다() {
    var quick =
        new CharacterLoadout.Skill(
            "마운틴 크래쉬",
            null,
            12,
            null,
            null,
            null,
            List.of(
                new CharacterLoadout.Tripod(
                    1, 2, "속공 준비", null, "스킬 사용 시 공격 속도가 4.0초 간 30.0% 증가한다.")),
            null,
            List.of(),
            CharacterLoadout.AttackType.NONE);
    var knight = node("깨달음 1티어 고독한 기사 Lv.3", "Z 스킬이 진격 태세 스킬로 변경된다. 진격 태세 동안 이동속도가 10.0% 감소한다.");
    var result = calculate("워로드", List.of(knight), List.of(quick), null, Assumptions.NONE);
    assertThat(result.toggles())
        .extracting(
            t ->
                t.key()
                    + "/"
                    + t.axis()
                    + "="
                    + t.value().stripTrailingZeros().toPlainString()
                    + "/"
                    + t.on())
        .contains("속공 준비/ATTACK_SPEED=30/false", "고독한 기사/MOVE_SPEED=-10/false");
    // 신속 1.32만
    assertThat(result.speed().attack().total()).isEqualByComparingTo("1.32");
    assertThat(result.speed().move().total()).isEqualByComparingTo("1.32");
  }

  /** PM-백엔드 10-10 01:46 P1: 켠 상황 조건 토글도 스킬별 치명타 적중률 기본에 들어간다. */
  @Test
  void 켠_치명타_적중률_토글은_스킬별_기본에도_들어간다() {
    var nodes =
        List.of(
            node(
                "깨달음 4티어 연가비기 Lv.3",
                "연가비기 사용 시 연가공법을 발동시켜 16.0초 동안 치명타 적중률이 20.0% 증가하며, 적중 시 최대 마나의 15.0%를 즉시 회복한다."));
    var skills = List.of(categorized("난무 스킬", "일반 스킬"));
    var off = calculate("창술사", nodes, skills, null, Assumptions.NONE);
    var on =
        calculate(
            "창술사",
            nodes,
            skills,
            null,
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                true,
                java.util.Map.of("연가비기", true)));
    assertThat(skill(on, "난무 스킬").base())
        .isEqualByComparingTo(skill(off, "난무 스킬").base().add(new BigDecimal("20")));
    assertThat(on.crit().totalRate())
        .isEqualByComparingTo(off.crit().totalRate().add(new BigDecimal("20")));
  }

  /** PHNTM 팔찌(2026-10-10 공식 문구): 적중 시 8초 효과는 상황 조건이라 기본 끔. */
  @Test
  void 팔찌_적중_시_8초_치명_저항_감소는_기본_끔_토글이다() {
    var bracelet =
        new CharacterLoadout.Equipment(
            "팔찌",
            "팔찌",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                null,
                null,
                4,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CharacterLoadout.BraceletEffect(
                        "몬스터에게 공격 적중 시 8초 동안 대상의 치명타 저항을 2.5% 감소시킨다.", null),
                    new CharacterLoadout.BraceletEffect(
                        "몬스터에게 공격 적중 시 8초 동안 대상의 치명타 피해 저항을 3.6% 감소시킨다.", null))));
    var profile =
        new CharacterProfile(
            "표본", "카단", "홀리나이트", 70, null, null, null, null, null, null, List.of());
    var loadout = new CharacterLoadout(List.of(bracelet), null, null, null, null, null);
    var off = CritSpeedCalculation.calculate(profile, loadout, Assumptions.NONE);
    assertThat(off.crit().totalRate()).isEqualByComparingTo("0");
    assertThat(off.toggles())
        .extracting(t -> t.key() + "/" + t.axis() + "/" + t.on())
        .containsExactly("팔찌 치명타 저항 감소/CRIT/false", "팔찌 치명타 피해 저항 감소/CRIT_DAMAGE/false");
    var on =
        CritSpeedCalculation.calculate(
            profile,
            loadout,
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                true,
                java.util.Map.of("팔찌 치명타 저항 감소", true)));
    assertThat(on.crit().totalRate()).isEqualByComparingTo("2.5");
  }

  @Test
  void 상황_조건_효과는_기본_끔이고_요청_토글로_켠다() {
    var nodes =
        List.of(
            node(
                "깨달음 4티어 연가비기 Lv.3",
                "연가비기 사용 시 연가공법을 발동시켜 16.0초 동안 치명타 적중률이 20.0% 증가하며, 적중 시 최대 마나의 15.0%를 즉시 회복한다."),
            node(
                "깨달음 3티어 단련 Lv.2",
                "우산 스킬 사용 후 치명타 적중률이 8.0초간 2.0% 증가하고, 기상 스킬 사용 후 치명타 피해량이 8.0초간 8.0% 증가한다. 단, 두 효과는 동시에 적용되지 않는다."),
            node(
                "깨달음 3티어 전술 훈련 Lv.1",
                "일반 스킬의 치명타 적중률이 0.8% 증가하며, 방어 태세 중에는 1.6% 증가한다. 전장의 방패 치명타 적중률이 1.6% 증가한다."),
            node(
                "깨달음 2티어 피스메이커 - 샷건 Lv.3",
                "샷건 스탠스로 변경 시 치명타 적중률이 10.0%, 적에게 주는 피해가 28.0% 증가하는 효과를 9초동안 획득한다."));
    var off = calculate("워로드", nodes, List.of(), null, Assumptions.NONE);
    // 전술 훈련은 "일반 스킬 0.8"을 기본으로 읽는다(방어 태세 1.6이 아님).
    assertThat(values(off, Axis.CRIT, Category.ARK_PASSIVE)).containsExactly("전술 훈련 Lv.1=0.8");
    assertThat(values(off, Axis.CRIT, Category.ASSUMPTION)).isEmpty();
    assertThat(off.toggles())
        .extracting(
            t -> t.key() + "=" + t.value().stripTrailingZeros().toPlainString() + "/" + t.on())
        .containsExactlyInAnyOrder(
            "연가비기=20/false",
            "단련=2/false",
            "단련 치명타 피해=8/false",
            "전술 훈련=0.8/false",
            "피스메이커 - 샷건=10/false");
    var on =
        calculate(
            "워로드",
            nodes,
            List.of(),
            null,
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                true,
                java.util.Map.of("연가비기", true, "전술 훈련", true, "단련 치명타 피해", true)));
    assertThat(values(on, Axis.CRIT, Category.ASSUMPTION))
        .containsExactlyInAnyOrder("연가공법 16초 유지 가정(연가비기 Lv.3)=20", "방어 태세 가정(전술 훈련 Lv.1)=0.8");
    assertThat(values(on, Axis.CRIT_DAMAGE, Category.ASSUMPTION))
        .containsExactly("기상 스킬 사용 후 8초 가정(단련 Lv.2)=8");
    assertThat(on.toggles())
        .filteredOn(t -> t.key().equals("단련 치명타 피해"))
        .extracting(CritSpeedCalculation.ToggleState::axis)
        .containsExactly(Axis.CRIT_DAMAGE);
    // 단련 두 효과는 "동시에 적용되지 않는다" → 둘 다 켜면 치명타 적중률 쪽만 적용한다.
    var both =
        calculate(
            "기상술사",
            nodes,
            List.of(),
            null,
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                true,
                java.util.Map.of("단련", true, "단련 치명타 피해", true)));
    assertThat(values(both, Axis.CRIT, Category.ASSUMPTION))
        .containsExactly("우산 스킬 사용 후 8초 가정(단련 Lv.2)=2");
    assertThat(values(both, Axis.CRIT_DAMAGE, Category.ASSUMPTION)).isEmpty();
    assertThat(both.toggles())
        .extracting(t -> t.key() + "/" + t.on())
        .contains("단련/true", "단련 치명타 피해/false");
    assertThat(both.limitations()).anyMatch(line -> line.contains("동시에 적용되지 않아 끔"));
    // 치명타 피해 토글은 스킬별 치명타 적중률 기본에 더하지 않는다.
    var damageOnly =
        calculate(
            "기상술사",
            nodes,
            List.of(categorized("우산", "일반 스킬")),
            null,
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                true,
                java.util.Map.of("단련 치명타 피해", true)));
    assertThat(skill(damageOnly, "우산").base())
        .isEqualByComparingTo(damageOnly.crit().observedRate());
  }

  @Test
  void 스킬_분류_한정_노드는_공식_분류가_같은_스킬에만_배수까지_더한다() {
    var nodes =
        List.of(
            node("깨달음 2티어 날카로운 해머 Lv.3", "해방 스킬 사용 중 소모한 코어 당 치명타 적중률이 6.0% 증가한다."),
            node("깨달음 3티어 분노의 망치 Lv.3", "해방 스킬 사용 시 소모한 코어 당 치명타 피해가 17.0% 증가한다."));
    var skills =
        List.of(
            categorized("풀 스윙", "해방 스킬"),
            categorized("헤비 크러쉬", "집속 스킬"),
            categorized("옛 캐시", null));
    var result = calculate("디스트로이어", nodes, skills, null, Assumptions.NONE);
    assertThat(values(result, Axis.CRIT, Category.ARK_PASSIVE)).isEmpty();
    var base = result.crit().observedRate();
    var damage = result.crit().damage().total();
    assertThat(skill(result, "풀 스윙").total()).isEqualByComparingTo(base.add(new BigDecimal("18")));
    assertThat(skill(result, "풀 스윙").damage())
        .isEqualByComparingTo(damage.add(new BigDecimal("51")));
    assertThat(skill(result, "헤비 크러쉬").total()).isEqualByComparingTo(base);
    assertThat(skill(result, "헤비 크러쉬").damage()).isEqualByComparingTo(damage);
    // 분류를 모르는 스킬(갱신 전 캐시)에는 추정해서 더하지 않는다.
    assertThat(skill(result, "옛 캐시").total()).isEqualByComparingTo(base);
    assertThat(result.limitations())
        .anyMatch(line -> line.contains("날카로운 해머 Lv.3 18%(해방 스킬에만, 코어 3개 소모)"))
        .anyMatch(line -> line.contains("분노의 망치 Lv.3 51%(해방 스킬에만, 코어 3개 소모)"));
  }

  private static CharacterLoadout.Skill categorized(String name, String category) {
    return new CharacterLoadout.Skill(
        name,
        null,
        12,
        null,
        null,
        null,
        List.of(),
        null,
        List.of(),
        CharacterLoadout.AttackType.NONE,
        category);
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
    // 스킬·트라이포드 버프 가정은 기본 끔(중복 기능은 로아랩 기준, PM 총괄 승인 10-10 18:26)
    var defaults = calculate("배틀마스터", List.of(), skills, null, Assumptions.NONE);
    assertThat(values(defaults, Axis.CRIT, Category.ASSUMPTION)).isEmpty();
    assertThat(defaults.toggles())
        .extracting(t -> t.key() + "/" + t.coreState() + "/" + t.on())
        .containsExactly("용맹의 포효 버프 유지 가정/false/false", "급소 노출 시너지(자신)/false/false");
    var on =
        calculate(
            "배틀마스터",
            List.of(),
            skills,
            null,
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                true,
                java.util.Map.of("용맹의 포효 버프 유지 가정", true, "급소 노출 시너지(자신)", true)));
    assertThat(values(on, Axis.CRIT, Category.ASSUMPTION))
        .containsExactly("용맹의 포효 버프 유지 가정=20", "급소 노출 시너지(자신)=10");
    // 아이덴티티 상태(폭주)는 기본 켬 유지
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
    assertThat(CritEffectTable.node("곡예사 Lv.1").damage()).isEqualTo(CritEffectTable.Decision.SKILL);
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
