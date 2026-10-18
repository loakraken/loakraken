package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreSupport;
import com.loakraken.character.domain.KrakenScoreTier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 크라켄 점수(비교 기준 B의 점수식, #522·#539·#559). 공식 전투력과 별개의 점수·티어다.
 *
 * <p>공식 armories를 받을 때 만든 엔진 입력(캐릭터 결과에 함께 캐시)으로 응답 때 계산한다 — 공식 호출이 없다. 설정 {@code
 * kraken-score.enabled}가 꺼져 있으면(기본) status DISABLED. 입력이 없는 이전 조회 결과는 UNSUPPORTED(INPUT_PENDING, 다시
 * 조회하면 계산), 계산할 수 없는 값(음수·NaN)은 UNSUPPORTED(SCORE_UNAVAILABLE), 계산 중 예외는 UNSUPPORTED(CALC_FAILED)이고
 * 근사값은 내지 않는다. 딜러는 딜러 점수, 서포터(서포터 각인 직업)는 서포터 점수식이다.
 */
@Service
public class KrakenScoreService {
  private static final Logger log = LoggerFactory.getLogger(KrakenScoreService.class);

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
    SCORE_UNAVAILABLE("계산한 점수가 음수이거나 수가 아니어서 표시하지 않습니다."),
    INPUT_PENDING("점수 입력이 없는 이전 조회 결과라 계산하지 않았습니다(다시 조회하면 계산)."),
    CALC_FAILED("점수를 계산하지 못했습니다.");

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
  private final KrakenScoreEngine.Tables tables;
  private final boolean enabled;
  private final MeterRegistry registry;
  private final Timer duration;

  public KrakenScoreService(
      KrakenScoreTier.Table tiers,
      KrakenScoreEngine.Tables tables,
      @Value("${kraken-score.enabled:false}") boolean enabled,
      MeterRegistry registry) {
    this.tiers = tiers;
    this.tables = tables;
    this.enabled = enabled;
    this.registry = registry;
    this.duration =
        Timer.builder("loakraken.kraken_score.duration")
            .description("응답 때 크라켄 점수 계산 시간")
            .publishPercentileHistogram()
            .register(registry);
  }

  public KrakenScore score(CharacterResult result) {
    if (!enabled)
      return new KrakenScore(
          Status.DISABLED, null, null, null, null, List.of(), tiers.ruleVersion());
    try {
      return compute(result);
    } catch (RuntimeException exception) {
      // 엔진 밖(역할 판정·티어 등)에서 난 예상 못 한 예외 — 켜진 기능이 '꺼짐'처럼 보이지 않게 실패로 낸다(로그에 캐릭터 이름 없음).
      log.warn(
          "event=kraken_score_failed stage=outside type={}", exception.getClass().getSimpleName());
      return failed();
    }
  }

  /** 켜진 상태에서 계산하지 못했을 때의 응답(UNSUPPORTED, CALC_FAILED). 컨트롤러의 마지막 안전망도 이 값을 쓴다. */
  public KrakenScore failed() {
    return unsupported(null, Reason.CALC_FAILED);
  }

  private KrakenScore compute(CharacterResult result) {
    JsonNode input = result.krakenScoreInput();
    if (input == null || !input.isObject()) return unsupported(null, Reason.INPUT_PENDING);
    // 딜러·서포터는 서비스 전체와 같은 규칙(CharacterPosition: 깨달음 노드). 입력의 supportCheck도 같은 규칙으로 만들었다.
    var details = result.details();
    var position =
        CharacterPosition.judge(details.profile().className(), details.loadout().arkPassive());
    if (position.role() == null) return unsupported(null, Reason.ROLE_UNKNOWN);
    var role = position.role() == CharacterPosition.Role.SUPPORT ? Role.SUPPORT : Role.DEALER;
    // 무기·아크 패시브가 비어 있으면 엔진 기본값으로 0점이 나오므로 계산하지 않는다(근사 금지).
    if (!input.path("armory").path("equipment").path("weapon").path("stat").isNumber()
        || !input.path("arkPassive").path("evolution").isObject())
      return unsupported(role, Reason.SCORE_UNAVAILABLE);
    double raw;
    var sample = Timer.start(registry);
    try {
      raw = rawScore(role, input);
    } catch (RuntimeException exception) {
      // 예상 못 한 입력 모양 — 500 대신 점수만 비우고, 점수 값 문제(SCORE_UNAVAILABLE)와 구별해 계산 실패로 낸다(로그에 캐릭터 이름 없음).
      log.warn(
          "event=kraken_score_failed stage=engine type={}", exception.getClass().getSimpleName());
      return unsupported(role, Reason.CALC_FAILED);
    } finally {
      sample.stop(duration);
    }
    return ok(role, raw, List.of());
  }

  /** 엔진 점수(반올림 전). 엔진 안 예외 처리를 테스트할 수 있게 나눠 둔다. */
  double rawScore(Role role, JsonNode input) {
    return role == Role.SUPPORT
        ? KrakenScoreSupport.score(input, tables)
        : KrakenScoreEngine.dealerScore(input, tables);
  }

  /**
   * 점수(반올림 전 double)로 응답을 만든다. 표시 점수는 원본 toFixed(2)처럼 double의 정확한 십진값을 둘째 자리 반올림하고, 티어는 반올림 전 값으로
   * 정한다. 음수·NaN·무한대는 점수·티어 없이 SCORE_UNAVAILABLE('-')이다.
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
    return new KrakenScore(
        Status.UNSUPPORTED, reason, role, null, null, List.of(), tiers.ruleVersion());
  }
}
