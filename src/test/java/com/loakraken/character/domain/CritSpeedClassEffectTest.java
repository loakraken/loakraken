package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CritSpeedCalculation.Assumptions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 치명·속도 직업 효과 세 가지(코드 리뷰 #979 P2 재실행, PM 총괄 11-21). 문구는 공식 Open API armories 캐릭터
 * 응답(ArkPassive.Effects[] 노드 툴팁, ArmorySkills[].Tripods[].Tooltip) 그대로이고, 2026-11-15 수집 직업 표본에서
 * 옮겼다(계산 검증 11-15 직업 원문, 이름 없음). 스탯 치명은 576(치명타 적중률 20.61%).
 */
class CritSpeedClassEffectTest {
  /** 2026-11-15 공식 원문: 디스트로이어 깨달음 2티어 '날카로운 해머' Lv.3 노드 효과. */
  private static final String SHARP_HAMMER = "해방 스킬 사용 중 소모한 코어 당 치명타 적중률이 6.0% 증가한다.";

  /** 2026-11-15 공식 원문: 디스트로이어 깨달음 3티어 '분노의 망치' Lv.3 노드 효과. */
  private static final String HAMMER_OF_WRATH = "해방 스킬 사용 시 소모한 코어 당 치명타 피해가 17.0% 증가한다.";

  /** 2026-11-15 공식 원문: 소울이터 아스타로스 트라이포드 '데런의 족쇄'. */
  private static final String DEREN = "치명타 적중률이 20.0% 증가하고 적에게 주는 피해가 100.0% 증가한다.";

  /** 2026-11-15 공식 원문: 아르카나 스트림 오브 엣지 트라이포드 '다크니스 엣지'(최대 레벨 문구). */
  private static final String DARKNESS_EDGE_MAX =
      "[암] 속성으로 변경되고, 적중 시 마다 아르카나의 치명타 적중률이 3.0초간 5.5% 씩 최대 27.6% 까지 증가한다.";

  /** 같은 트라이포드의 낮은 레벨 문구(코드 리뷰 #979 재실행이 든 꼴, 최대 레벨 문구와 숫자만 다름). */
  private static final String DARKNESS_EDGE_LOW =
      "[암] 속성으로 변경되고, 적중 시 마다 아르카나의 치명타 적중률이 3.0초간 3.0% 씩 최대 15.0% 까지 증가한다.";

  /**
   * 디스트로이어 해방 스킬 노드 두 개(코어 3개 소모 가정 ×3)는 합계에는 들어가지만 스킬별로는 '[해방 스킬]' 분류 스킬에만 붙는다. 집속 스킬(헤비 크러쉬)은 스탯
   * 치명 그대로(20.61% · 200%), 해방 스킬(풀 스윙)은 +18%p · +51%p.
   */
  @Test
  void 디스트로이어_해방_스킬_노드는_해방_스킬에만_붙는다() {
    var loadout =
        loadout(
            List.of(
                node("깨달음 2티어 날카로운 해머 Lv.3", SHARP_HAMMER),
                node("깨달음 3티어 분노의 망치 Lv.3", HAMMER_OF_WRATH)),
            List.of(
                skill("헤비 크러쉬", 14, "집속 스킬", List.of()), skill("풀 스윙", 14, "해방 스킬", List.of())));
    var result = CritSpeedCalculation.calculate(profile("디스트로이어"), loadout, Assumptions.NONE);
    var heavy = skill(result, "헤비 크러쉬");
    var swing = skill(result, "풀 스윙");
    assertThat(heavy.total()).isEqualByComparingTo("20.61");
    assertThat(heavy.damage()).isEqualByComparingTo("200");
    assertThat(swing.total()).isEqualByComparingTo("38.61");
    assertThat(swing.damage()).isEqualByComparingTo("251");
  }

  /**
   * 소울이터 '데런의 족쇄'는 직업 버프 가정(기본 켬)으로 모든 스킬 기본에 한 번 들어간다. 그 트라이포드를 단 아스타로스도 트라이포드로 또 세지 않는다(40.61%, 두
   * 번 세면 60.61%).
   */
  @Test
  void 데런의_족쇄는_아스타로스에_한_번만_센다() {
    var loadout =
        loadout(
            List.of(),
            List.of(
                skill("아스타로스", 14, "일반 스킬", List.of(tripod("데런의 족쇄", DEREN))),
                skill("다른 스킬", 14, "일반 스킬", List.of())));
    var result = CritSpeedCalculation.calculate(profile("소울이터"), loadout, Assumptions.NONE);
    assertThat(skill(result, "아스타로스").total()).isEqualByComparingTo("40.61");
    assertThat(skill(result, "다른 스킬").total()).isEqualByComparingTo("40.61");
  }

  /**
   * 아르카나 '다크니스 엣지'는 트라이포드 문구의 '최대 X%'를 읽는다(고정값 27.6 없음). 낮은 레벨 문구(최대 15.0%)면 15.0, 최대 레벨 문구면 27.6.
   */
  @Test
  void 다크니스_엣지는_문구의_최대_중첩_값을_읽는다() {
    for (var entry :
        List.of(
            List.of(DARKNESS_EDGE_LOW, "15.0", "35.61"),
            List.of(DARKNESS_EDGE_MAX, "27.6", "48.21"))) {
      var loadout =
          loadout(
              List.of(),
              List.of(skill("스트림 오브 엣지", 10, "일반 스킬", List.of(tripod("다크니스 엣지", entry.get(0))))));
      var result = CritSpeedCalculation.calculate(profile("아르카나"), loadout, Assumptions.NONE);
      var toggle =
          result.toggles().stream()
              .filter(t -> t.key().equals("다크니스 엣지 최대 중첩 가정"))
              .findFirst()
              .orElseThrow();
      assertThat(toggle.value()).as(entry.get(0)).isEqualByComparingTo(entry.get(1));
      assertThat(skill(result, "스트림 오브 엣지").total())
          .as(entry.get(0))
          .isEqualByComparingTo(entry.get(2));
    }
  }

  /** 문구에서 최대 중첩 값을 읽지 못하면 고정값으로 채우지 않고 가정을 넣지 않는다. */
  @Test
  void 다크니스_엣지_문구를_읽지_못하면_가정을_넣지_않는다() {
    var loadout =
        loadout(
            List.of(),
            List.of(
                skill(
                    "스트림 오브 엣지",
                    10,
                    "일반 스킬",
                    List.of(tripod("다크니스 엣지", "[암] 속성으로 변경되고, 적중 시 마다 아르카나의 치명타 적중률이 증가한다.")))));
    var result = CritSpeedCalculation.calculate(profile("아르카나"), loadout, Assumptions.NONE);
    assertThat(result.toggles()).noneMatch(t -> t.key().equals("다크니스 엣지 최대 중첩 가정"));
    assertThat(skill(result, "스트림 오브 엣지").total()).isEqualByComparingTo("20.61");
  }

  private static CritSpeedCalculation.SkillCrit skill(
      CritSpeedCalculation.Result result, String name) {
    return result.skills().stream()
        .filter(s -> s.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError(name + " 없음: " + result.skills()));
  }

  private static CharacterProfile profile(String className) {
    return new CharacterProfile(
        "기준캐릭터",
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
  }

  private static CharacterLoadout loadout(
      List<CharacterLoadout.PassiveEffect> nodes, List<CharacterLoadout.Skill> skills) {
    return new CharacterLoadout(
        List.of(),
        null,
        null,
        null,
        new CharacterLoadout.ArkPassive("아크패시브", true, List.of(), nodes),
        null,
        skills);
  }

  private static CharacterLoadout.PassiveEffect node(String description, String effect) {
    return new CharacterLoadout.PassiveEffect("깨달음", description, null, effect);
  }

  private static CharacterLoadout.Skill skill(
      String name, int level, String category, List<CharacterLoadout.Tripod> tripods) {
    return new CharacterLoadout.Skill(
        name,
        null,
        level,
        null,
        null,
        null,
        tripods,
        null,
        List.of(),
        CharacterLoadout.AttackType.NONE,
        category,
        List.of(),
        List.of(),
        List.of());
  }

  private static CharacterLoadout.Tripod tripod(String name, String effect) {
    return new CharacterLoadout.Tripod(1, 1, name, null, effect);
  }
}
