package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 직업별 치명 효과 정답표(crit-speed-v4~, #189·#198). 계산 검증 [[직업별 치명 효과 정답표]](2026-10-09, 일로아 55명·로펙 53명 대조)를
 * 공식 노드 문구(같은 날 표본 50명 armory)와 맞춰 고정한 표다.
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
          // "… '퀵 드로우' 버프가 9초간 지속된다. 퀵 드로우 : 치명타 적중률이 1.0% 증가한다." 두 사이트 포함(계산 검증 10-10 01:55)
          new NodeRule("퀵 드로우", Decision.INCLUDE, null),
          new NodeRule("라이플 숙련", Decision.INCLUDE, null),
          new NodeRule("급소 전문가", null, Decision.EXCLUDE),
          new NodeRule("정밀 사격 훈련", Decision.INCLUDE, Decision.INCLUDE),
          new NodeRule("포격 출력 강화", Decision.SKILL, null),
          new NodeRule("신속 포격", null, Decision.EXCLUDE),
          new NodeRule("코어 인챈트", Decision.INCLUDE, null),
          new NodeRule("페일 노트", null, Decision.INCLUDE),
          new NodeRule("마에스트로", Decision.INCLUDE, null),
          new NodeRule("격노의 악장", null, Decision.INCLUDE),
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

  /**
   * 노드 문구에 없는 직업 버프. 직업·장착 스킬·선택 트라이포드로 판정한다. 값은 정답표(두 사이트 일치) 값이고 문구가 있으면 문구 값을 쓴다. defaultOn:
   * 아이덴티티·각성 상태(폭주)는 기본 켬, 스킬·트라이포드 버프 가정은 기본 끔(중복 기능은 로아랩 기준, PM 총괄 승인 10-10 18:26, 계산 검증 「치명 규칙
   * 로아랩 재대조」).
   */
  public record ClassBuff(
      String label,
      String className,
      String skill,
      String tripod,
      BigDecimal value,
      String basis,
      boolean defaultOn) {}

  public static final List<ClassBuff> CLASS_BUFFS =
      List.of(
          new ClassBuff(
              "폭주 상태 가정",
              "슬레이어",
              null,
              null,
              new BigDecimal("30"),
              "슬레이어 폭주 상태 치명타 적중률(아이덴티티 상태, 기본 켬)",
              true),
          new ClassBuff(
              "용맹의 포효 버프 유지 가정",
              "배틀마스터",
              "용맹의 포효",
              null,
              new BigDecimal("20"),
              "용맹의 포효 8초 버프(스킬·트라이포드 버프 가정, 기본 끔)",
              false),
          new ClassBuff(
              "번개의 속삭임 버프 유지 가정",
              "스트라이커",
              "번개의 속삭임",
              null,
              new BigDecimal("10"),
              "번개의 속삭임 12초 버프(스킬·트라이포드 버프 가정, 기본 끔)",
              false),
          new ClassBuff(
              "급소 노출 시너지(자신)",
              null,
              null,
              "급소 노출",
              new BigDecimal("10"),
              "자기 스킬 트라이포드 '급소 노출' 치명타 적중률 시너지(스킬·트라이포드 버프 가정, 기본 끔)",
              false),
          new ClassBuff(
              "나를 위한 연주 버프 상시 유지 가정",
              "바드",
              null,
              "나를 위한 연주",
              new BigDecimal("20"),
              "천상의 연주 트라이포드 '나를 위한 연주'(스킬·트라이포드 버프 가정, 기본 끔)",
              false),
          new ClassBuff(
              "빛의 성장 버프 상시 유지 가정",
              "서머너",
              null,
              "빛의 성장",
              new BigDecimal("11.8"),
              "슈르디 트라이포드 '빛의 성장'(스킬·트라이포드 버프 가정, 기본 끔)",
              false));

  /** 공용 아크 그리드 코어. 두 사이트가 기본에 넣는다. 직업 코어의 치명 효과는 일로아 기준으로 넣지 않는다. */
  public static final Map<String, CritSpeedCalculation.Axis> COMMON_CORES =
      Map.of(
          "부수는 일격", CritSpeedCalculation.Axis.CRIT,
          "재빠른 공격", CritSpeedCalculation.Axis.CRIT_DAMAGE);

  static final Pattern ANY_CRIT = Pattern.compile("치명타 (?:적중률|피해)");

  /**
   * 직업 아크 그리드 코어(질서의 해·달·별) 판정(계산 검증 10-10 01:55, PM-백엔드 01:46). INCLUDE는 달성 구간의 "치명타 적중률·피해량이 X%
   * 증가한다." 문장을 기본에 더하고, WEAK_POINT는 급소 노출(치적 시너지)로, SITUATIONAL은 기본 끔 토글로 둔다. 표에 없는 직업 코어의 치명 문구는
   * "분류 중"으로 남긴다.
   */
  public enum CoreDecision {
    INCLUDE,
    WEAK_POINT,
    SITUATIONAL
  }

  public record ClassCore(String core, CoreDecision decision, String label) {}

  public static final List<ClassCore> CLASS_CORES =
      List.of(
          // 스트라이커 질서의 별 "초극뇌연격" 18~20P "치명타 피해량이 0.52% 증가한다." — 두 사이트 포함(환업 +1.56)
          new ClassCore("초극뇌연격", CoreDecision.INCLUDE, null),
          // 데빌헌터 질서의 해 "샷건 오버로드" 14P "… 급소 노출 효과가 적용되어 … 치명타 저항률이 5.0초 동안 10.0% 감소" — 두 사이트 시너지 10
          new ClassCore("샷건 오버로드", CoreDecision.WEAK_POINT, null),
          // 워로드 질서의 해 "천둥" 17P "'운명: 천둥'을 소모하면 20.0초 간 … 자신의 치명타 적중률이 6.0% 증가" — 상황 조건(PM 23:24)
          new ClassCore("천둥", CoreDecision.SITUATIONAL, "운명: 천둥의 가호 20초 가정"));

  static ClassCore classCore(String slotName) {
    if (slotName == null) return null;
    for (var core : CLASS_CORES) if (slotName.endsWith(" : " + core.core())) return core;
    return null;
  }

  /**
   * 팔찌의 상황 조건 효과(적중 시 8초, 기본 끔 토글, PM-백엔드 01:46 (가)). 대상의 치명타 저항 감소는 치명타 적중률, 치명타 피해 저항 감소는 치명타 피해로
   * 본다.
   */
  public record BraceletToggle(
      String key, CritSpeedCalculation.Axis axis, String label, Pattern pattern) {}

  public static final List<BraceletToggle> BRACELET_TOGGLES =
      List.of(
          new BraceletToggle(
              "팔찌 치명타 저항 감소",
              CritSpeedCalculation.Axis.CRIT,
              "팔찌 치명타 저항 감소 8초 가정",
              Pattern.compile("공격 적중 시 8초 동안 대상의 치명타 저항을\\s*([0-9]+(?:\\.[0-9]+)?)% 감소")),
          new BraceletToggle(
              "팔찌 치명타 피해 저항 감소",
              CritSpeedCalculation.Axis.CRIT_DAMAGE,
              "팔찌 치명타 피해 저항 감소 8초 가정",
              Pattern.compile("공격 적중 시 8초 동안 대상의 치명타 피해 저항을\\s*([0-9]+(?:\\.[0-9]+)?)% 감소")));

  /**
   * 아크 패시브 노드의 공격·이동 속도 판정(#198). INCLUDE는 기본에 넣고, CORE_STATE는 기본 켬 토글, SITUATIONAL은 기본 끔 토글,
   * EXCLUDE는 다른 대상(소환수·특정 스킬)의 속도라 넣지 않는다. 표에 없는 노드의 속도 문구는 합산하지 않고 "분류 중"으로 남긴다.
   */
  public enum SpeedDecision {
    INCLUDE,
    EXCLUDE,
    CORE_STATE,
    SITUATIONAL
  }

  public record SpeedRule(String node, SpeedDecision decision, String label, String basis) {}

  private static final List<SpeedRule> SPEED_RULES =
      List.of(
          // 두 사이트 일치(c09 기민함 48 = 신속 29.25 + 질풍노도 12)
          new SpeedRule("질풍노도", SpeedDecision.INCLUDE, null, "기본 포함"),
          // "진화형 피해가 12.0% 증가하고, 공격 속도가 4.0% 증가합니다."
          new SpeedRule("파괴 전차", SpeedDecision.INCLUDE, null, "조건 없는 문구"),
          // "이동 속도가 10.0% 증가하고, …"
          new SpeedRule("고대의 바람", SpeedDecision.INCLUDE, null, "조건 없는 문구"),
          // "전투 중 자신 및 주변 파티원에게 '전투 축복 III' … (20초 지속, 매 초마다 갱신) … 공격 및 이동 속도 9.0% 증가"
          new SpeedRule("축복의 여신", SpeedDecision.INCLUDE, null, "전투 중 매 초 갱신"),
          // 직업 핵심 상태(각성형 변신·직업 게이지)
          new SpeedRule("광기", SpeedDecision.CORE_STATE, "광기 상태 가정", "광기 상태에서는 공격속도 및 이동속도가 15.0%"),
          new SpeedRule("환수 각성", SpeedDecision.CORE_STATE, "환수 각성 상태 가정", "환수 각성 상태에서는 … 20.0% 증가"),
          new SpeedRule(
              "고독한 기사", SpeedDecision.SITUATIONAL, "진격 태세 가정", "기본 제외(진격 태세 동안 이동속도가 10.0% 감소)"),
          new SpeedRule(
              "두 번째 동료", SpeedDecision.CORE_STATE, "실버호크 소환 가정", "실버호크 소환 중 … 이동속도가 8.0% 증가"),
          new SpeedRule(
              "아르데타인의 기술", SpeedDecision.CORE_STATE, "드론 부착 가정", "드론이 부착된 상태에서 자신의 이동속도가 10.0% 증가"),
          // 상황 조건(스킬·전환 직후 N초, 모드·스탠스)
          new SpeedRule("달의 소리", SpeedDecision.SITUATIONAL, "달의 소리 효과 가정", "기본 제외(페르소나 전환 시)"),
          new SpeedRule("피스메이커 - 핸드건", SpeedDecision.SITUATIONAL, "핸드건 스탠스 변경 후 9초 가정", "9초동안"),
          new SpeedRule("평화주의자", SpeedDecision.SITUATIONAL, "스탠스 변경 후 12초 가정", "12.0초동안"),
          new SpeedRule("전술 이동", SpeedDecision.SITUATIONAL, "이동기 사용 후 8초 가정", "8.0초 동안"),
          new SpeedRule("고속 진입", SpeedDecision.SITUATIONAL, "간섭 사용 후 20초 가정", "20.0초 동안"),
          new SpeedRule("징벌의 서막", SpeedDecision.SITUATIONAL, "징벌 스킬 사용 후 5초 가정", "5.0초간"),
          new SpeedRule("잔불", SpeedDecision.SITUATIONAL, "화신 해제 후 16초 가정", "16.0초간"),
          new SpeedRule("폭풍의 사냥꾼", SpeedDecision.SITUATIONAL, "사냥꾼의 발걸음 5초 가정", "5.0초 동안"),
          new SpeedRule("마나 순환", SpeedDecision.SITUATIONAL, "마나 순환 효과 가정", "마력 방출 종료 시 최대 30초"),
          new SpeedRule("중력 가속", SpeedDecision.SITUATIONAL, "중력 모드 가정", "중력 가중영역 및 중력 해방 모드일 때"),
          new SpeedRule("절정 I", SpeedDecision.SITUATIONAL, "스탠스 전환 가정", "난무·집중 스탠스 전환 시"),
          new SpeedRule(
              "깨어난 잠재력", SpeedDecision.SITUATIONAL, "여우 둔갑 가정", "여우 둔갑 : 공격 속도가 20.0% 증가"),
          new SpeedRule("핸드 거너", SpeedDecision.SITUATIONAL, "맹공 6초 가정", "'끊임없는 맹공' 사용 시 6초"),
          new SpeedRule("황후의 속삭임", SpeedDecision.SITUATIONAL, "루인 적중 후 6초 가정", "6.0초간"),
          new SpeedRule("정령 폭주", SpeedDecision.SITUATIONAL, "아키르 폭주 가정", "8.0~12.0초간"),
          // 자신이 아닌 대상의 속도
          new SpeedRule("넘치는 교감", SpeedDecision.EXCLUDE, null, "소환수(마리린·파우루·엘씨드·슈르디) 문장"),
          new SpeedRule("야성", SpeedDecision.EXCLUDE, null, "둔갑 스킬의 공격속도"),
          new SpeedRule("난무 강화", SpeedDecision.EXCLUDE, null, "오의 스킬의 공격속도"),
          new SpeedRule("막을 수 없는 분노", SpeedDecision.EXCLUDE, null, "블러드러스트의 공격 속도"),
          new SpeedRule("초비행", SpeedDecision.EXCLUDE, null, "활공의 이동 속도"));

  static final Map<String, SpeedRule> SPEED =
      SPEED_RULES.stream()
          .collect(Collectors.toUnmodifiableMap(SpeedRule::node, Function.identity()));

  /**
   * 선택 트라이포드의 공이속(#198 재대조 10-10 02:10). 표에 없는 트라이포드의 공이속 문구는 더하지 않고 "분류 중"으로 남긴다. 갈림 항목(일로아만 포함)은
   * PM 총괄 23:24 기준(공식 문구가 상황 조건 → 기본 끔 토글)으로 두고 PM 판단을 기다린다.
   */
  public record TripodSpeed(String tripod, SpeedDecision decision, String label, String basis) {}

  public static final List<TripodSpeed> TRIPOD_SPEEDS =
      List.of(
          // 건슬링어 기동 타격 "구르기 이후 8.0초 동안 공격 속도와 이동 속도가 19.2% 증가한다." — 두 사이트 포함(c10)
          new TripodSpeed("날렵함", SpeedDecision.SITUATIONAL, "날렵함 8초 유지 가정", "트라이포드 버프 가정, 기본 끔"),
          // 배틀마스터 붕천퇴 "적에게 콤보 공격 적중 시 4.0초간 공격속도가 19.2% 증가하고 이동속도가 19.2% 증가한다." — 일로아만(리쿠)
          new TripodSpeed(
              "바람의 축복", SpeedDecision.SITUATIONAL, "바람의 축복 4초 가정", "공식 문구 상황 조건이라 기본 끔"),
          // 버서커 마운틴 크래쉬 "스킬 사용 시 공격 속도가 4.0초 간 30.0% 증가한다." — 일로아만(TheG0D)
          new TripodSpeed("속공 준비", SpeedDecision.SITUATIONAL, "속공 준비 4초 가정", "공식 문구 상황 조건이라 기본 끔"));

  static TripodSpeed tripodSpeed(String name) {
    for (var rule : TRIPOD_SPEEDS) if (rule.tripod().equals(name)) return rule;
    return null;
  }

  /** 노드 문구에 없는 직업 자기 버프의 공이속. label은 같은 상태의 치명 버프와 같아 한 토글로 켜고 끈다. */
  public record ClassSpeedBuff(
      String label, String className, BigDecimal attack, BigDecimal move) {}

  public static final List<ClassSpeedBuff> CLASS_SPEED_BUFFS =
      List.of(
          // 슬레이어 폭주: 두 사이트 공이속 +20(c02 21.86/31.86 → 41.86/51.86, 계산 검증 10-10 02:10)
          new ClassSpeedBuff("폭주 상태 가정", "슬레이어", new BigDecimal("20"), new BigDecimal("20")));

  static SpeedRule speed(String nodeName) {
    if (nodeName == null) return null;
    return SPEED.get(nodeName.replaceFirst("\\s+Lv\\.\\d+$", "").strip());
  }
}
