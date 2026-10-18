package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.KrakenScoreBracelet;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreSupport;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 팔찌 실 효율: 캐시된 크라켄 점수 입력(#559)으로 원본 정보 탭의 팔찌 % 함수를 돌린다. 딜러는 팔찌 총배율 하나, 서포터는 점수 두 번이라 1ms 안팎이어서 응답 때
 * 계산한다. 공식 호출이 없다. 원본 화면 설정 '악마 피해'(기본 꺼짐)를 켠 딜러 값도 함께 낸다.
 */
@Service
public class BraceletRealService {
  static final String INPUT_PENDING = "INPUT_PENDING";
  static final String ROLE_UNKNOWN = "ROLE_UNKNOWN";
  static final String UNAVAILABLE = "UNAVAILABLE";
  private static final Logger log = LoggerFactory.getLogger(BraceletRealService.class);

  private final KrakenScoreEngine.Tables tables;

  public BraceletRealService(KrakenScoreEngine.Tables tables) {
    this.tables = tables;
  }

  public BraceletRealEfficiency compute(CharacterResult result) {
    var input = result.krakenScoreInput();
    if (input == null || !input.isObject())
      return BraceletRealEfficiency.unsupported(
          INPUT_PENDING, "점수 입력이 없는 이전 조회 결과라 계산하지 않았습니다(다시 조회하면 계산).", null);
    var details = result.details();
    var position =
        CharacterPosition.judge(details.profile().className(), details.loadout().arkPassive());
    if (position.role() == null)
      return BraceletRealEfficiency.unsupported(ROLE_UNKNOWN, "딜러·서포터를 판정하지 못해 계산하지 않았습니다.", null);
    var role = position.role().name();
    var bangle = input.path("armory").path("accessory").path("bangle");
    // 원본은 팔찌가 없거나 티어·옵션이 비면 총배율 1을 돌려준다 — 그 값은 팔찌 효율이 아니라서 내지 않는다.
    if (!bangle.isObject() || bangle.path("option").isEmpty() || !bangle.path("tier").isNumber())
      return BraceletRealEfficiency.unsupported(UNAVAILABLE, "팔찌 옵션이 없어 계산하지 않았습니다.", role);
    if (!input.path("armory").path("equipment").path("weapon").path("stat").isNumber()
        || !input.path("arkPassive").path("evolution").isObject())
      return BraceletRealEfficiency.unsupported(UNAVAILABLE, "무기나 아크 패시브 정보가 없어 계산하지 않았습니다.", role);
    try {
      boolean supporter = position.role() == CharacterPosition.Role.SUPPORT;
      double value = supporter ? support(input) : dealer(input, false);
      // '악마 피해' 설정은 원본에서 딜러만 받는다(서포터 팔찌 %는 설정과 무관).
      Double devil = supporter ? null : dealer(input, true);
      if (!Double.isFinite(value) || (devil != null && !Double.isFinite(devil)))
        return BraceletRealEfficiency.unsupported(UNAVAILABLE, "팔찌 효율을 계산하지 못했습니다.", role);
      return new BraceletRealEfficiency(
          BraceletRealEfficiency.Status.OK,
          null,
          null,
          role,
          BigDecimal.valueOf(value),
          devil == null ? null : BigDecimal.valueOf(devil),
          BraceletRealEfficiency.RULE_VERSION);
    } catch (RuntimeException exception) {
      // 예상 못 한 입력 모양 — 응답은 막지 않고 이 값만 비운다(로그에 캐릭터 이름 없음).
      log.warn("event=bracelet_real_failed type={}", exception.getClass().getSimpleName());
      return BraceletRealEfficiency.unsupported(UNAVAILABLE, "팔찌 효율을 계산하지 못했습니다.", role);
    }
  }

  /** 원본 딜러 팔찌 %(12921 sh): 팔찌 총배율 × 점수 상수 − 1을 % 둘째 자리 내림. devil = 설정 '악마 피해'. */
  private double dealer(JsonNode input, boolean devil) {
    double total = KrakenScoreBracelet.total(input, tables, devil);
    return Math.floor(100 * ((total * KrakenScoreBracelet.SCORE_CONSTANT - 1) * 100)) / 100;
  }

  /** 원본 서포터 팔찌 %: (팔찌 있는 점수 ÷ 팔찌 뺀 점수 − 1)을 % 둘째 자리 내림한 뒤 ÷ 1.4. 뺀 점수가 0 이하면 NaN. */
  private double support(JsonNode input) {
    double with = KrakenScoreSupport.score(input, tables);
    var copy = input.deepCopy();
    ((ObjectNode) copy.path("armory").path("accessory")).putNull("bangle");
    double without = KrakenScoreSupport.score(copy, tables);
    if (!(without > 0)) return Double.NaN;
    return Math.floor((with / without - 1) * 1e4) / 100 / 1.4;
  }
}
