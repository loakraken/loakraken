package com.loakraken.character.presentation;

import com.loakraken.character.application.CritSpeedService;
import com.loakraken.character.domain.CritSpeedCalculation;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "치명타·공이속 계산. 관측(OBSERVED)과 가정(ASSUMED) 출처를 분리합니다. 값은 % 단위입니다.")
public record CritSpeedResponse(
    @Schema(description = "계산 규칙 버전") String ruleVersion,
    String characterName,
    @NullableResponse String className,
    @Schema(description = "캐릭터 원천 수집 시각(캐시 적중 시 원래 시각)") Instant fetchedAt,
    @Schema(description = "합산한 출처. text는 공식 원문 또는 가정 설명") List<CritSpeedSourceResponse> sources,
    CritSpeedCritResponse crit,
    CritSpeedSpeedResponse speed,
    @Schema(description = "계산에서 제외했거나 근사한 항목") List<String> limitations,
    @Schema(
            description =
                "스킬별 치명타 적중률(레벨 2 이상 스킬, 이름순). base(crit.observedRate) + 백 어택 +10 + 선택 트라이포드 치명타 적중률 합. 가정 입력은 더하지 않는다")
        List<CritSpeedSkillResponse> skills) {

  static CritSpeedResponse from(CritSpeedService.CritSpeedResult value) {
    var result = value.result();
    var crit = result.crit();
    var thorn = crit.bluntThorn();
    var speed = result.speed();
    var supersonic = speed.supersonic();
    return new CritSpeedResponse(
        result.ruleVersion(),
        value.characterName(),
        value.className(),
        value.fetchedAt(),
        result.sources().stream().map(CritSpeedSourceResponse::from).toList(),
        new CritSpeedCritResponse(
            crit.critStat(),
            crit.observedRate(),
            crit.assumedRate(),
            crit.totalRate(),
            crit.effectiveRate(),
            new CritSpeedBluntThornResponse(
                thorn.source(),
                thorn.rule().level(),
                thorn.rule().baseEvolutionDamage(),
                thorn.rule().critCap(),
                thorn.rule().conversionPercent(),
                thorn.rule().maxEvolutionDamage(),
                thorn.optimalCritRate(),
                thorn.evolutionDamage(),
                thorn.effectiveCritRate()),
            crit.targets().stream()
                .map(
                    target ->
                        new CritSpeedTargetResponse(
                            target.key(),
                            target.targetRate(),
                            target.requiredCritStat(),
                            target.requiredAdditionalCritStat(),
                            target.reached()))
                .toList(),
            crit.critStatPerPercent(),
            new CritSpeedCritDamageResponse(
                crit.damage().base(), crit.damage().observed(), crit.damage().total())),
        new CritSpeedSpeedResponse(
            speed.swiftnessStat(),
            speed.cap(),
            CritSpeedAxisResponse.from(speed.attack()),
            CritSpeedAxisResponse.from(speed.move()),
            new CritSpeedSupersonicResponse(
                supersonic.source(),
                supersonic.rule().level(),
                supersonic.rule().conversionPercent(),
                supersonic.rule().overCapBonus(),
                supersonic.rule().overCapConversionPercent(),
                supersonic.rule().maxEvolutionDamage(),
                supersonic.optimalSpeed(),
                supersonic.requiredSwiftness(),
                supersonic.requiredAdditionalSwiftness(),
                supersonic.evolutionDamage()),
            speed.swiftnessPerPercent(),
            speed.swiftnessTable().stream()
                .map(
                    row ->
                        new CritSpeedSwiftnessRowResponse(
                            row.swiftness(),
                            row.attack(),
                            row.move(),
                            row.attackEffective(),
                            row.moveEffective(),
                            row.supersonicEvolutionDamage(),
                            row.current(),
                            row.optimal()))
                .toList()),
        result.limitations(),
        result.skills().stream().map(CritSpeedSkillResponse::from).toList());
  }

  public record CritSpeedSkillResponse(
      String name,
      @Schema(description = "BACK_ATTACK·HEAD_ATTACK·NONE. 모르면 null") @NullableResponse
          com.loakraken.character.domain.CharacterLoadout.AttackType attackType,
      @Schema(description = "crit.observedRate") BigDecimal base,
      @Schema(description = "백 어택이면 10, 아니면 0. 공격 타입을 모르면 null") @NullableResponse
          BigDecimal backAttack,
      @Schema(description = "선택 트라이포드 치명타 적중률 합. 갱신 전 캐시라 문구가 없으면 null") @NullableResponse
          BigDecimal tripod,
      @Schema(description = "base + backAttack + tripod. 하나라도 null이면 null") @NullableResponse
          BigDecimal total,
      @Schema(description = "total에 상한(100, 뭉툭한 가시 장착 시 그 상한) 적용") @NullableResponse
          BigDecimal effective,
      @Schema(description = "합산한 트라이포드") List<CritSpeedSkillTripodResponse> tripods,
      @Schema(description = "조건부 문구('…시', '…의 치명타 적중률')라 합산하지 않은 트라이포드")
          List<CritSpeedSkillTripodResponse> conditional,
      @Schema(description = "이 스킬에만 붙는 아크 패시브 노드 치명타 적중률 합(예: 관통 필살 → 적룡필살)") BigDecimal node,
      @Schema(description = "합산한 스킬 한정 노드(공식 문구 포함)") List<CritSpeedSkillTripodResponse> nodes) {
    static CritSpeedSkillResponse from(CritSpeedCalculation.SkillCrit skill) {
      return new CritSpeedSkillResponse(
          skill.name(),
          skill.attackType(),
          skill.base(),
          skill.backAttack(),
          skill.tripod(),
          skill.total(),
          skill.effective(),
          skill.tripods().stream().map(CritSpeedSkillTripodResponse::from).toList(),
          skill.conditional().stream().map(CritSpeedSkillTripodResponse::from).toList(),
          skill.node(),
          skill.nodes().stream().map(CritSpeedSkillTripodResponse::from).toList());
    }
  }

  public record CritSpeedSkillTripodResponse(
      String name,
      @Schema(description = "치명타 적중률 증가(%)") BigDecimal value,
      @Schema(description = "공식 트라이포드 효과 문구") String text) {
    static CritSpeedSkillTripodResponse from(CritSpeedCalculation.SkillTripod tripod) {
      return new CritSpeedSkillTripodResponse(tripod.name(), tripod.value(), tripod.text());
    }
  }

  public record CritSpeedSourceResponse(
      CritSpeedCalculation.Kind kind,
      CritSpeedCalculation.Category category,
      CritSpeedCalculation.Axis axis,
      @NullableResponse String name,
      BigDecimal value,
      @NullableResponse String text) {
    static CritSpeedSourceResponse from(CritSpeedCalculation.Source source) {
      return new CritSpeedSourceResponse(
          source.kind(),
          source.category(),
          source.axis(),
          source.name(),
          source.value(),
          source.text());
    }
  }

  public record CritSpeedCritResponse(
      @Schema(description = "프로필 치명 스탯. 알 수 없으면 null") @NullableResponse Integer critStat,
      BigDecimal observedRate,
      BigDecimal assumedRate,
      @Schema(description = "관측+가정 치명타 적중률(상한 적용 전)") BigDecimal totalRate,
      @Schema(description = "실제 적용 치명타 적중률. 뭉툭한 가시 장착 시 그 상한, 아니면 100%") BigDecimal effectiveRate,
      CritSpeedBluntThornResponse bluntThorn,
      @Schema(description = "CRIT_100: 치적 100%, BLUNT_THORN_OPTIMAL: 뭉툭한 가시 최대 진화형 피해 도달")
          List<CritSpeedTargetResponse> targets,
      @Schema(description = "치명타 적중률 1%당 치명 스탯(27.944)") BigDecimal critStatPerPercent,
      @Schema(description = "치명타 피해(%). 기본 200 + 관측 출처(axis=CRIT_DAMAGE) 합. 스킬 한정 문구 제외")
          CritSpeedCritDamageResponse damage) {}

  public record CritSpeedCritDamageResponse(
      @Schema(description = "기본 치명타 피해(200)") BigDecimal base,
      @Schema(description = "관측 출처 합") BigDecimal observed,
      @Schema(description = "base + observed") BigDecimal total) {}

  public record CritSpeedBluntThornResponse(
      @Schema(description = "EQUIPPED_NODE: 장착 노드 공식 문구, REFERENCE_LEVEL_2: 미장착이라 공식 Lv.2 문구 기준")
          CritSpeedCalculation.NodeSource source,
      @NullableResponse Integer level,
      BigDecimal baseEvolutionDamage,
      BigDecimal critCap,
      @Schema(description = "상한 초과 치적의 진화형 피해 전환 비율 %") BigDecimal conversionPercent,
      BigDecimal maxEvolutionDamage,
      BigDecimal optimalCritRate,
      @Schema(description = "현재 totalRate에서의 노드 진화형 피해") BigDecimal evolutionDamage,
      @Schema(description = "노드 장착 시 적용 치적") BigDecimal effectiveCritRate) {}

  public record CritSpeedTargetResponse(
      String key,
      BigDecimal targetRate,
      @Schema(description = "목표에 필요한 치명 스탯 총량. 치명 스탯을 모르면 null") @NullableResponse
          Integer requiredCritStat,
      @Schema(description = "추가로 필요한 치명(올림)") int requiredAdditionalCritStat,
      boolean reached) {}

  public record CritSpeedSpeedResponse(
      @NullableResponse Integer swiftnessStat,
      @Schema(description = "공격·이동 속도 증가 상한(%)") BigDecimal cap,
      CritSpeedAxisResponse attack,
      CritSpeedAxisResponse move,
      CritSpeedSupersonicResponse supersonic,
      @Schema(description = "공격·이동 속도 1%당 신속 스탯(58.22)") BigDecimal swiftnessPerPercent,
      @Schema(description = "신속 스탯별 공이속 표. 0부터 200 간격, 현재값·최적값 행 포함(최대 4000)")
          List<CritSpeedSwiftnessRowResponse> swiftnessTable) {}

  public record CritSpeedSwiftnessRowResponse(
      int swiftness,
      @Schema(description = "신속 외 출처(관측+가정)를 더한 공격 속도 증가 %") BigDecimal attack,
      BigDecimal move,
      @Schema(description = "상한 적용") BigDecimal attackEffective,
      BigDecimal moveEffective,
      @Schema(description = "음속 돌파 진화형 피해(장착 노드 또는 Lv.2 기준값)") BigDecimal supersonicEvolutionDamage,
      @Schema(description = "현재 신속 행") boolean current,
      @Schema(description = "음속 돌파 최적 신속 행") boolean optimal) {}

  public record CritSpeedAxisResponse(
      BigDecimal observed,
      BigDecimal assumed,
      BigDecimal total,
      @Schema(description = "상한 적용") BigDecimal effective) {
    static CritSpeedAxisResponse from(CritSpeedCalculation.SpeedAxis axis) {
      return new CritSpeedAxisResponse(
          axis.observed(), axis.assumed(), axis.total(), axis.effective());
    }
  }

  public record CritSpeedSupersonicResponse(
      CritSpeedCalculation.NodeSource source,
      @NullableResponse Integer level,
      @Schema(description = "상한 이내 공격·이동 속도 증가량의 진화형 피해 전환 %") BigDecimal conversionPercent,
      @Schema(description = "둘 다 상한 초과 시 추가 진화형 피해") BigDecimal overCapBonus,
      @Schema(description = "상한 초과분의 진화형 피해 전환 %") BigDecimal overCapConversionPercent,
      BigDecimal maxEvolutionDamage,
      @Schema(description = "공격·이동 속도를 같게 올릴 때 최대 진화형 피해에 닿는 속도 증가량") BigDecimal optimalSpeed,
      @Schema(description = "optimalSpeed에 필요한 신속 총량. 신속 스탯을 모르면 null") @NullableResponse
          Integer requiredSwiftness,
      int requiredAdditionalSwiftness,
      @Schema(description = "현재 속도에서의 노드 진화형 피해") BigDecimal evolutionDamage) {}
}
