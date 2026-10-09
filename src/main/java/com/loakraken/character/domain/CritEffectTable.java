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
 *   <li>INCLUDE: 두 사이트가 기본 치적·치피에 넣는다. EXCLUDE: 두 사이트가 뺀다. SPLIT: 사이트가 갈린다(일로아 기준으로 빼고 "로펙은 포함"을
 *       남긴다).
 *   <li>표에 없는 노드의 치명 문구는 합산하지 않고 미분류로 남긴다(지표·테스트로 잡는다).
 *   <li>노드 문구에 없는 직업 버프(폭주 상태, 용맹의 포효 등)는 직업·스킬·트라이포드 기준 가정으로 더한다.
 * </ul>
 */
public final class CritEffectTable {
  private CritEffectTable() {}

  public enum Decision {
    INCLUDE,
    EXCLUDE,
    SPLIT
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
          new NodeRule("곡예사", null, Decision.SPLIT),
          new NodeRule("그림자 맹수", Decision.EXCLUDE, Decision.EXCLUDE),
          new NodeRule("영혼친화력", Decision.INCLUDE, null),
          new NodeRule("어둠의 장송곡", null, Decision.EXCLUDE),
          new NodeRule("점화의 불씨", Decision.INCLUDE, null),
          new NodeRule("발화", null, Decision.INCLUDE),
          new NodeRule("혼돈 강화", Decision.SPLIT, null),
          new NodeRule("잠식 제어", Decision.INCLUDE, null),
          new NodeRule("날카로운 해머", Decision.SPLIT, null),
          new NodeRule("분노의 망치", null, Decision.SPLIT),
          new NodeRule("영역 강화", Decision.EXCLUDE, null),
          new NodeRule("중력 수련", Decision.EXCLUDE, null),
          new NodeRule("정교함", Decision.INCLUDE, null),
          new NodeRule("전술 훈련", Decision.INCLUDE, null),
          new NodeRule("치명적인 체술", null, Decision.SPLIT),
          new NodeRule("체술 강화", Decision.INCLUDE, null),
          new NodeRule("무상진결", Decision.INCLUDE, null),
          new NodeRule("운기행공", Decision.INCLUDE, null),
          new NodeRule("날카로운 기공", null, Decision.INCLUDE),
          new NodeRule("연가비기", Decision.SPLIT, null),
          new NodeRule("절정 II", null, Decision.INCLUDE),
          // 공식 문구 "…치명타 적중률이 25.0% 증가한다."는 조건이 없어 기본 포함(정답표 6-1, 일로아가 뺀 것은 틀림)
          new NodeRule("회귀", Decision.INCLUDE, null),
          // 스킬 한정(정답표 6-1): 기본에서 빼고 그 스킬의 스킬별 치적에만 더한다
          new NodeRule("관통 필살", Decision.EXCLUDE, null),
          new NodeRule("충격 폭발", Decision.EXCLUDE, null),
          new NodeRule("암살자의 발자취", Decision.EXCLUDE, null),
          new NodeRule("평화주의자", Decision.INCLUDE, null),
          new NodeRule("라이플 숙련", Decision.INCLUDE, null),
          new NodeRule("급소 전문가", null, Decision.EXCLUDE),
          new NodeRule("정밀 사격 훈련", Decision.INCLUDE, Decision.INCLUDE),
          new NodeRule("포격 출력 강화", Decision.SPLIT, null),
          new NodeRule("신속 포격", null, Decision.EXCLUDE),
          new NodeRule("코어 인챈트", Decision.INCLUDE, null),
          new NodeRule("페일 노트", null, Decision.INCLUDE),
          new NodeRule("마에스트로", Decision.INCLUDE, null),
          new NodeRule("격노의 악장", null, Decision.EXCLUDE),
          new NodeRule("교감 강화", Decision.SPLIT, null),
          new NodeRule("고대의 힘", Decision.SPLIT, null),
          new NodeRule("기민함", Decision.INCLUDE, Decision.INCLUDE),
          new NodeRule("단련", Decision.SPLIT, Decision.SPLIT),
          new NodeRule("가속 강화", Decision.INCLUDE, null),
          new NodeRule("분침 강화", Decision.INCLUDE, null),
          new NodeRule("깨어난 잠재력", Decision.SPLIT, null),
          new NodeRule("환수의 정기", null, Decision.SPLIT),
          new NodeRule("깨어나는 힘", Decision.INCLUDE, null),
          new NodeRule("완전 연소", Decision.INCLUDE, null),
          new NodeRule("할버드의 대가", null, Decision.SPLIT));

  static final Map<String, NodeRule> NODES =
      RULES.stream().collect(Collectors.toUnmodifiableMap(NodeRule::node, Function.identity()));

  /** "광기 Lv.3" → 광기의 규칙. 없으면 null. */
  public static NodeRule node(String nodeName) {
    if (nodeName == null) return null;
    return NODES.get(nodeName.replaceFirst("\\s+Lv\\.\\d+$", "").strip());
  }

  /**
   * 스킬 한정 노드(정답표 6-1). skill이 null이면 백 어택 스킬 전부다. classState면 직업 상태 가정(요청 classState)과 함께 켜고 끈다.
   */
  public record SkillBonus(String node, String skill, boolean classState) {}

  public static final List<SkillBonus> SKILL_BONUSES =
      List.of(
          // "혼돈 상태일 때 피니쉬 스텝의 … 백어택 적중 시 추가로 치명타 적중률이 20.0% 증가"(Lv.3), 일로아: 백 어택 스킬에만
          new SkillBonus("암살자의 발자취", null, true),
          // "적룡필살의 돌진 공격 거리가 … 치명타 적중률이 100.0% 증가"
          new SkillBonus("관통 필살", "적룡필살", false),
          // "성운멸쇄권 스킬 시전 시 치명타 적중률이 20.0% 증가"
          new SkillBonus("충격 폭발", "성운멸쇄권", false),
          // "피니쉬 스텝의 치명타 적중률이 30.0% 증가"
          new SkillBonus("그림자 맹수", "피니쉬 스텝", false));

  static SkillBonus skillBonus(String base) {
    for (var bonus : SKILL_BONUSES) if (bonus.node().equals(base)) return bonus;
    return null;
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
