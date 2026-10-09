package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 직업별 치명 효과 정답표(crit-speed-v4, #189). 계산 검증 [[직업별 치명 효과 정답표]](2026-10-09, 일로아 55명·로펙 53명 대조)를 공식 노드
 * 문구(같은 날 표본 50명 armory)와 맞춰 고정한 표다.
 *
 * <ul>
 *   <li>아크 패시브 노드는 이름으로 판정한다. 문구 키워드로 추측하지 않는다.
 *   <li>INCLUDE: 기본 치적·치피에 넣는다. EXCLUDE: 넣지 않는다. SKILL: 공식 문구의 대상 스킬(이름·스킬 분류·백 어택)의 스킬별 치적·치피에만
 *       넣는다. TOGGLE: 켜고 끄는 효과(핵심 상태는 기본 켬, 상황 조건은 기본 끔). 갈림 항목은 PM 총괄 23:00·23:24 기준(다수결, 그다음 공식
 *       Tooltip 조건 문구)으로 이 넷 중 하나로 정했다.
 *   <li>표에 없는 노드의 치명 문구는 합산하지 않고 미분류로 남긴다(지표·테스트로 잡는다).
 *   <li>노드 문구에 없는 직업 버프(폭주 상태, 용맹의 포효 등)는 직업·스킬·트라이포드 기준 가정으로 더한다.
 * </ul>
 */
public final class CritEffectTable {
  private CritEffectTable() {}

  public enum Decision {
    INCLUDE,
    EXCLUDE,
    /** 공식 문구의 대상 스킬에만(SkillBonus 표). */
    SKILL,
    /** 기본에 넣지 않고 켜고 끄는 효과(PM 총괄 23:24). Toggle 표를 따른다. */
    TOGGLE
  }

  /** rate·damage는 그 노드의 치명타 적중률·치명타 피해 판정이다. 해당 효과가 없으면 null. */
  public record NodeRule(String node, Decision rate, Decision damage) {}

  private static final List<NodeRule> RULES =
      List.of(
          // 0. 공통(진화)
          new NodeRule("예리한 감각", Decision.INCLUDE, null),
          new NodeRule("혼신의 강타", Decision.INCLUDE, null),
          new NodeRule("일격", Decision.INCLUDE, Decision.EXCLUDE),
          new NodeRule("달인", Decision.INCLUDE, null),
          // 1. 직업 각인
          new NodeRule("격분", null, Decision.INCLUDE),
          new NodeRule("광기", Decision.INCLUDE, null),
          new NodeRule("분노 자극", null, Decision.INCLUDE),
          new NodeRule("피냄새", Decision.INCLUDE, null),
          new NodeRule("유령 무희", Decision.INCLUDE, null),
          new NodeRule("곡예사", null, Decision.SKILL),
          new NodeRule("그림자 맹수", Decision.SKILL, Decision.EXCLUDE),
          new NodeRule("영혼친화력", Decision.INCLUDE, null),
          new NodeRule("어둠의 장송곡", null, Decision.EXCLUDE),
          new NodeRule("점화의 불씨", Decision.INCLUDE, null),
          new NodeRule("발화", null, Decision.INCLUDE),
          new NodeRule("혼돈 강화", Decision.TOGGLE, null),
          new NodeRule("잠식 제어", Decision.INCLUDE, null),
          new NodeRule("날카로운 해머", Decision.SKILL, null),
          new NodeRule("분노의 망치", null, Decision.SKILL),
          new NodeRule("영역 강화", Decision.EXCLUDE, null),
          new NodeRule("중력 수련", Decision.EXCLUDE, null),
          new NodeRule("정교함", Decision.INCLUDE, null),
          new NodeRule("전술 훈련", Decision.INCLUDE, null),
          new NodeRule("치명적인 체술", null, Decision.SKILL),
          new NodeRule("체술 강화", Decision.INCLUDE, null),
          new NodeRule("무상진결", Decision.INCLUDE, null),
          new NodeRule("운기행공", Decision.INCLUDE, null),
          new NodeRule("날카로운 기공", null, Decision.INCLUDE),
          new NodeRule("연가비기", Decision.TOGGLE, null),
          new NodeRule("절정 II", null, Decision.INCLUDE),
          // 공식 문구 "…치명타 적중률이 25.0% 증가한다."는 조건이 없어 기본 포함(정답표 6-1, 일로아가 뺀 것은 틀림)
          new NodeRule("회귀", Decision.INCLUDE, null),
          new NodeRule("관통 필살", Decision.SKILL, null),
          new NodeRule("충격 폭발", Decision.SKILL, null),
          new NodeRule("암살자의 발자취", Decision.SKILL, null),
          new NodeRule("평화주의자", Decision.INCLUDE, null),
          new NodeRule("라이플 숙련", Decision.INCLUDE, null),
          new NodeRule("급소 전문가", null, Decision.EXCLUDE),
          new NodeRule("정밀 사격 훈련", Decision.INCLUDE, Decision.INCLUDE),
          new NodeRule("포격 출력 강화", Decision.SKILL, null),
          new NodeRule("신속 포격", null, Decision.EXCLUDE),
          new NodeRule("코어 인챈트", Decision.INCLUDE, null),
          new NodeRule("페일 노트", null, Decision.INCLUDE),
          new NodeRule("마에스트로", Decision.INCLUDE, null),
          new NodeRule("격노의 악장", null, Decision.EXCLUDE),
          new NodeRule("교감 강화", Decision.SKILL, null),
          new NodeRule("고대의 힘", Decision.SKILL, null),
          new NodeRule("기민함", Decision.INCLUDE, Decision.INCLUDE),
          new NodeRule("단련", Decision.TOGGLE, Decision.TOGGLE),
          new NodeRule("피스메이커 - 샷건", Decision.TOGGLE, null),
          new NodeRule("가속 강화", Decision.INCLUDE, null),
          new NodeRule("분침 강화", Decision.INCLUDE, null),
          new NodeRule("깨어난 잠재력", Decision.SKILL, null),
          new NodeRule("환수의 정기", null, Decision.SKILL),
          new NodeRule("깨어나는 힘", Decision.INCLUDE, null),
          new NodeRule("완전 연소", Decision.INCLUDE, null),
          new NodeRule("할버드의 대가", null, Decision.SKILL));

  static final Map<String, NodeRule> NODES =
      RULES.stream().collect(Collectors.toUnmodifiableMap(NodeRule::node, Function.identity()));

  /** "광기 Lv.3" → 광기의 규칙. 없으면 null. */
  public static NodeRule node(String nodeName) {
    if (nodeName == null) return null;
    return NODES.get(nodeName.replaceFirst("\\s+Lv\\.\\d+$", "").strip());
  }

  /** 스킬 한정 효과의 대상. SKILL은 스킬 이름, CATEGORY는 공식 스킬 Tooltip 제목의 분류("[해방 스킬]"), BACK_ATTACK은 공격 타입. */
  public enum Target {
    SKILL,
    CATEGORY,
    BACK_ATTACK
  }

  /**
   * 스킬 한정 노드(정답표 6-1, PM-백엔드 10-10 01:16). 값은 공식 문구 첫 값 × multiplier다. classState면 직업 핵심
   * 상태(혼돈·페르소나·환수 각성) 조건이라 직업 상태 가정(요청 classState)과 함께 켜고 끈다.
   */
  public record SkillBonus(
      String node,
      CritSpeedCalculation.Axis axis,
      Target target,
      String skill,
      int multiplier,
      boolean classState,
      String basis) {}

  public static final List<SkillBonus> SKILL_BONUSES =
      List.of(
          // "혼돈 상태일 때 … 백어택 적중 시 추가로 치명타 적중률이 20.0% 증가"
          new SkillBonus(
              "암살자의 발자취", CritSpeedCalculation.Axis.CRIT, Target.BACK_ATTACK, null, 1, true, null),
          // "적룡필살의 돌진 공격 거리가 … 치명타 적중률이 100.0% 증가"
          new SkillBonus(
              "관통 필살", CritSpeedCalculation.Axis.CRIT, Target.SKILL, "적룡필살", 1, false, null),
          // "성운멸쇄권 스킬 시전 시 치명타 적중률이 20.0% 증가"
          new SkillBonus(
              "충격 폭발", CritSpeedCalculation.Axis.CRIT, Target.SKILL, "성운멸쇄권", 1, false, null),
          // "피니쉬 스텝의 치명타 적중률이 30.0% 증가"
          new SkillBonus(
              "그림자 맹수", CritSpeedCalculation.Axis.CRIT, Target.SKILL, "피니쉬 스텝", 1, false, null),
          // "해방 스킬 사용 중 소모한 코어 당 치명타 적중률이 6.0% 증가" — 디스트로이어 코어 최대 3개(정답표 6-1 ×3)
          new SkillBonus(
              "날카로운 해머",
              CritSpeedCalculation.Axis.CRIT,
              Target.CATEGORY,
              "해방 스킬",
              3,
              false,
              "코어 3개 소모"),
          // "해방 스킬 사용 시 소모한 코어 당 치명타 피해가 17.0% 증가"
          new SkillBonus(
              "분노의 망치",
              CritSpeedCalculation.Axis.CRIT_DAMAGE,
              Target.CATEGORY,
              "해방 스킬",
              3,
              false,
              "코어 3개 소모"),
          // "포격 스킬의 … 치명타 적중률이 40.0% 증가" — 포격 스킬은 전투 스킬 목록에 없어(아이덴티티) 표시만 한다
          new SkillBonus(
              "포격 출력 강화", CritSpeedCalculation.Axis.CRIT, Target.CATEGORY, "포격 스킬", 1, false, null),
          // "소환 스킬의 치명타 적중률이 2.8% 증가"
          new SkillBonus(
              "교감 강화", CritSpeedCalculation.Axis.CRIT, Target.CATEGORY, "소환 스킬", 1, false, null),
          // "일반 및 고대의 정령 스킬의 치명타 적중률이 16.0% 증가" — 고대의 정령 스킬은 전투 스킬 목록에 없다
          new SkillBonus(
              "고대의 힘", CritSpeedCalculation.Axis.CRIT, Target.CATEGORY, "일반 스킬", 1, false, null),
          // "둔갑 스킬 치명타 적중률이 30.0% 증가"
          new SkillBonus(
              "깨어난 잠재력", CritSpeedCalculation.Axis.CRIT, Target.CATEGORY, "둔갑 스킬", 1, false, null),
          // "환수 각성 상태에서 환수 스킬의 치명타 피해량이 60.0% 증가"
          new SkillBonus(
              "환수의 정기",
              CritSpeedCalculation.Axis.CRIT_DAMAGE,
              Target.CATEGORY,
              "환수 스킬",
              1,
              true,
              null),
          // "일반 스킬의 치명타 피해량이 4.0% 증가"
          new SkillBonus(
              "할버드의 대가",
              CritSpeedCalculation.Axis.CRIT_DAMAGE,
              Target.CATEGORY,
              "일반 스킬",
              1,
              false,
              null),
          // "일반 스킬의 치명타 피해량이 6.0% 증가"
          new SkillBonus(
              "치명적인 체술",
              CritSpeedCalculation.Axis.CRIT_DAMAGE,
              Target.CATEGORY,
              "일반 스킬",
              1,
              false,
              null),
          // "페르소나 상태일 때 급습 스킬 적중 시 적에게 주는 치명타 피해량이 3.0% 증가"
          new SkillBonus(
              "곡예사",
              CritSpeedCalculation.Axis.CRIT_DAMAGE,
              Target.CATEGORY,
              "급습 스킬",
              1,
              true,
              null));

  static SkillBonus skillBonus(String base, CritSpeedCalculation.Axis axis) {
    for (var bonus : SKILL_BONUSES)
      if (bonus.node().equals(base) && bonus.axis() == axis) return bonus;
    return null;
  }

  /**
   * 켜고 끄는 치명타 적중률 효과(PM 총괄 23:24, 정답표 6-4). coreState면 직업 핵심 상태(각성형 변신·직업 게이지)라 기본 켬, 아니면 상황 조건이라
   * 기본 끔이다. pattern이 null이면 첫 "치명타 적중률이 X%" 값을 쓴다. incremental이면 그 노드의 기본 값을 뺀 차이만 더한다(전술 훈련 방어 태세
   * 1.6 - 기본 0.8). exclusiveWith는 동시에 적용되지 않는 다른 토글 key다. 둘 다 켜면 exclusiveWith 쪽만 적용한다.
   */
  public record Toggle(
      String node,
      String key,
      CritSpeedCalculation.Axis axis,
      String label,
      boolean coreState,
      Pattern pattern,
      boolean incremental,
      String exclusiveWith) {
    Toggle(String node, String label, boolean coreState, Pattern pattern, boolean incremental) {
      this(
          node, node, CritSpeedCalculation.Axis.CRIT, label, coreState, pattern, incremental, null);
    }
  }

  public static final List<Toggle> TOGGLES =
      List.of(
          // "악마화 중 치명타 적중률이 30.0% 증가한다."
          new Toggle("혼돈 강화", "악마화 상태 가정", true, null, false),
          // "연가비기 사용 시 연가공법을 발동시켜 16.0초 동안 치명타 적중률이 20.0% 증가"
          new Toggle("연가비기", "연가공법 16초 유지 가정", false, null, false),
          // "우산 스킬 사용 후 치명타 적중률이 8.0초간 2.0% 증가 … 두 효과는 동시에 적용되지 않는다"
          new Toggle(
              "단련",
              "우산 스킬 사용 후 8초 가정",
              false,
              Pattern.compile("치명타 적중률이\\s*[0-9.]+초간\\s*([0-9]+(?:\\.[0-9]+)?)%"),
              false),
          // "기상 스킬 사용 후 치명타 피해량이 8.0초간 8.0% 증가한다. 단, 두 효과는 동시에 적용되지 않는다."
          new Toggle(
              "단련",
              "단련 치명타 피해",
              CritSpeedCalculation.Axis.CRIT_DAMAGE,
              "기상 스킬 사용 후 8초 가정",
              false,
              Pattern.compile("치명타 피해량이\\s*[0-9.]+초간\\s*([0-9]+(?:\\.[0-9]+)?)%"),
              false,
              // "단, 두 효과는 동시에 적용되지 않는다." → 치명타 적중률 쪽("단련")을 켜면 이 토글은 꺼진다
              "단련"),
          // "샷건 스탠스로 변경 시 치명타 적중률이 10.0% … 9초동안"
          new Toggle("피스메이커 - 샷건", "샷건 스탠스 변경 후 9초 가정", false, null, false),
          // "일반 스킬의 치명타 적중률이 0.8% 증가하며, 방어 태세 중에는 1.6% 증가한다."
          new Toggle(
              "전술 훈련",
              "방어 태세 가정",
              false,
              Pattern.compile("방어 태세 중에는\\s*([0-9]+(?:\\.[0-9]+)?)%"),
              true));

  static List<Toggle> toggles(String base) {
    return TOGGLES.stream().filter(toggle -> toggle.node().equals(base)).toList();
  }

  /** 노드 문구에 없는 직업 버프. 직업·장착 스킬·선택 트라이포드로 판정한다. 값은 정답표(두 사이트 일치) 값이고 문구가 있으면 문구 값을 쓴다. */
  public record ClassBuff(
      String label,
      String className,
      String skill,
      String tripod,
      BigDecimal value,
      String basis) {}

  public static final List<ClassBuff> CLASS_BUFFS =
      List.of(
          new ClassBuff(
              "폭주 상태 가정",
              "슬레이어",
              null,
              null,
              new BigDecimal("30"),
              "슬레이어 폭주 상태 치명타 적중률(두 사이트 기본 포함)"),
          new ClassBuff(
              "용맹의 포효 버프 유지 가정",
              "배틀마스터",
              "용맹의 포효",
              null,
              new BigDecimal("20"),
              "용맹의 포효 8초 버프(두 사이트 기본 포함)"),
          new ClassBuff(
              "번개의 속삭임 버프 유지 가정",
              "스트라이커",
              "번개의 속삭임",
              null,
              new BigDecimal("10"),
              "번개의 속삭임 12초 버프(두 사이트 기본 포함)"),
          new ClassBuff(
              "급소 노출 시너지(자신)",
              null,
              null,
              "급소 노출",
              new BigDecimal("10"),
              "자기 스킬 트라이포드 '급소 노출' 치명타 적중률 시너지(두 사이트 기본 포함)"),
          new ClassBuff(
              "나를 위한 연주 버프 상시 유지 가정",
              "바드",
              null,
              "나를 위한 연주",
              new BigDecimal("20"),
              "천상의 연주 트라이포드 '나를 위한 연주'(두 사이트 기본 포함)"),
          new ClassBuff(
              "빛의 성장 버프 상시 유지 가정",
              "서머너",
              null,
              "빛의 성장",
              new BigDecimal("11.8"),
              "슈르디 트라이포드 '빛의 성장'(두 사이트 기본 포함)"));

  /** 공용 아크 그리드 코어. 두 사이트가 기본에 넣는다. 직업 코어의 치명 효과는 일로아 기준으로 넣지 않는다. */
  public static final Map<String, CritSpeedCalculation.Axis> COMMON_CORES =
      Map.of(
          "부수는 일격", CritSpeedCalculation.Axis.CRIT,
          "재빠른 공격", CritSpeedCalculation.Axis.CRIT_DAMAGE);

  static final Pattern ANY_CRIT = Pattern.compile("치명타 (?:적중률|피해)");
}
