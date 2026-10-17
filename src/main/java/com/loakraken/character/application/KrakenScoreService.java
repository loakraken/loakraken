package com.loakraken.character.application;

import com.loakraken.character.domain.KrakenScoreTier;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 크라켄 점수(비교 기준 B의 점수식, #522). 공식 전투력과 별개의 점수·티어다.
 *
 * <p>단계적으로 붙인다: 이 단계는 응답 모양·티어·켜기 설정만 있고, 요소(공격력·진피·…·팔찌) 계산은 다음 단계다. 설정 {@code kraken-score.enabled}가
 * 꺼져 있으면(기본) status DISABLED, 켜져 있어도 요소를 아직 계산하지 않으면 UNSUPPORTED다. 근사값은 내지 않는다. 공식 호출이 없다.
 */
@Service
public class KrakenScoreService {
  public enum Status {
    OK,
    UNSUPPORTED,
    DISABLED
  }

  public enum Role {
    DEALER,
    SUPPORT
  }

  /** 이유 코드. reason 문장은 로그·디버그용이고 화면은 code로 그린다. */
  public enum Reason {
    ROLE_UNKNOWN("딜러·서포터를 판정하지 못해 계산하지 않았습니다."),
    FACTOR_PENDING("점수 요소를 아직 계산하지 않는 단계입니다."),
    SCORE_UNAVAILABLE("계산한 점수가 음수이거나 수가 아니어서 표시하지 않습니다.");

    private final String text;

    Reason(String text) {
      this.text = text;
    }

    public String text() {
      return text;
    }
  }

  /** 요소 하나. code는 열린 값(단계마다 늘어남). */
  public record Factor(String code, BigDecimal value, Status status, Reason reason) {}

  /** status가 OK가 아니면 score·tier는 null, factors는 빈 목록(DISABLED)이거나 계산한 요소까지. */
  public record KrakenScore(
      Status status,
      Reason reason,
      Role role,
      BigDecimal score,
      KrakenScoreTier.Tier tier,
      List<Factor> factors,
      String ruleVersion) {}

  private final KrakenScoreTier.Table tiers;
  private final boolean enabled;
  private final MeterRegistry registry;

  public KrakenScoreService(
      KrakenScoreTier.Table tiers,
      @Value("${kraken-score.enabled:false}") boolean enabled,
      MeterRegistry registry) {
    this.tiers = tiers;
    this.enabled = enabled;
    this.registry = registry;
  }

  public KrakenScore score(CharacterResult result) {
    if (!enabled)
      return new KrakenScore(Status.DISABLED, null, null, null, null, List.of(), tiers.ruleVersion());
    var roleReason = CombatPowerSimulationService.roleReason(result);
    Role role = null;
    if (roleReason == null) role = Role.DEALER;
    else if (CombatPowerSimulationService.SUPPORTER_BASIS.equals(roleReason)) role = Role.SUPPORT;
    var reason = role == null ? Reason.ROLE_UNKNOWN : Reason.FACTOR_PENDING;
    return unsupported(role, reason);
  }

  /**
   * 점수(반올림 전 double)로 응답을 만든다. 다음 단계에서 요소 계산이 이 경로를 쓴다. 표시 점수는 원본 toFixed(2)처럼 double의 정확한 십진값을 둘째
   * 자리 반올림하고, 티어는 반올림 전 값으로 정한다. 음수·NaN·무한대는 점수·티어 없이 SCORE_UNAVAILABLE('-')이다.
   */
  KrakenScore ok(Role role, double rawScore, List<Factor> factors) {
    if (!Double.isFinite(rawScore) || rawScore < 0)
      return unsupported(role, Reason.SCORE_UNAVAILABLE);
    return new KrakenScore(
        Status.OK,
        null,
        role,
        new BigDecimal(rawScore).setScale(2, java.math.RoundingMode.HALF_UP),
        KrakenScoreTier.of(tiers, rawScore),
        List.copyOf(factors),
        tiers.ruleVersion());
  }

  private KrakenScore unsupported(Role role, Reason reason) {
    // 공개 뒤 '점수 안 나옴'이 얼마나 되는지 보려고 이유별로 센다(observability).
    registry.counter("loakraken.kraken_score.unsupported", "reason", reason.name()).increment();
    return new KrakenScore(Status.UNSUPPORTED, reason, role, null, null, List.of(), tiers.ruleVersion());
  }
}
