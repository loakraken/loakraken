package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreGems;
import com.loakraken.character.domain.KrakenScoreSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 아크 그리드 젬 실 효율(#520): 공식 조회 때 만든 크라켄 점수 입력으로 원본 젬 실 효율 함수(전체·질서·혼돈)를 돌린다. 한 번에 수 밀리초~10여 밀리초라 응답 때가
 * 아니라 공식 조회 때 한 번 계산해 캐릭터 결과와 함께 캐시한다. 딜러는 딜러 점수, 서포터는 서포터 젬 효율 점수(원본 GT)로 비교한다. 공식 호출이 없다.
 */
@Service
public class ArkGridRealService {
  static final String INPUT_PENDING = "INPUT_PENDING";
  static final String ROLE_UNKNOWN = "ROLE_UNKNOWN";
  static final String UNAVAILABLE = "UNAVAILABLE";
  private static final Logger log = LoggerFactory.getLogger(ArkGridRealService.class);

  private final KrakenScoreEngine.Tables tables;

  public ArkGridRealService(KrakenScoreEngine.Tables tables) {
    this.tables = tables;
  }

  public ArkGridRealEfficiency compute(CharacterDetails details, JsonNode input) {
    if (input == null || !input.isObject())
      return ArkGridRealEfficiency.unsupported(
          INPUT_PENDING, "점수 입력이 없는 이전 조회 결과라 계산하지 않았습니다(다시 조회하면 계산).");
    if (input.path("arkGrid").path("core").isEmpty())
      return ArkGridRealEfficiency.unsupported(UNAVAILABLE, "아크 그리드 코어가 없어 계산하지 않았습니다.");
    var position =
        CharacterPosition.judge(details.profile().className(), details.loadout().arkPassive());
    if (position.role() == null)
      return ArkGridRealEfficiency.unsupported(ROLE_UNKNOWN, "딜러·서포터를 판정하지 못해 계산하지 않았습니다.");
    if (!input.path("armory").path("equipment").path("weapon").path("stat").isNumber()
        || !input.path("arkPassive").path("evolution").isObject())
      return ArkGridRealEfficiency.unsupported(UNAVAILABLE, "무기나 아크 패시브 정보가 없어 계산하지 않았습니다.");
    ToDoubleFunction<JsonNode> score =
        position.role() == CharacterPosition.Role.SUPPORT
            ? e -> KrakenScoreSupport.gemScore(e, tables)
            : e -> KrakenScoreEngine.dealerScore(e, tables);
    try {
      var total = KrakenScoreGems.total(input, tables, score);
      var bySeries = KrakenScoreGems.bySeries(input, tables, score);
      var effects = input.path("arkGrid").path("gemEffects");
      var options = new ArrayList<ArkGridRealEfficiency.Option>();
      for (int i = 0; i < total.lines().size(); i++) {
        var line = total.lines().get(i);
        var effect = effects.path(i).path("effect");
        options.add(
            new ArkGridRealEfficiency.Option(
                line.name(),
                level(line.level()),
                effect.isNumber() ? BigDecimal.valueOf(effect.asDouble()) : null,
                percent(line.efficiency())));
      }
      var series = new ArrayList<ArkGridRealEfficiency.Series>();
      for (var entry : bySeries.entrySet()) {
        var value = entry.getValue();
        if (value == null) continue;
        series.add(
            new ArkGridRealEfficiency.Series(
                entry.getKey(),
                percent(value.efficiency()),
                percent(value.optionEfficiency()),
                percent(value.pointEfficiency()),
                value.lines().stream()
                    .map(
                        line ->
                            new ArkGridRealEfficiency.SeriesOption(
                                line.name(), level(line.level()), percent(line.efficiency())))
                    .toList()));
      }
      return new ArkGridRealEfficiency(
          ArkGridRealEfficiency.Status.OK,
          null,
          null,
          percent(total.efficiency()),
          percent(total.optionEfficiency()),
          percent(total.pointEfficiency()),
          options,
          series,
          ArkGridRealEfficiency.RULE_VERSION);
    } catch (RuntimeException exception) {
      // 예상 못 한 입력 모양 — 조회는 막지 않고 실효율만 비운다(로그에 캐릭터 이름 없음).
      log.warn("event=ark_grid_real_failed type={}", exception.getClass().getSimpleName());
      return ArkGridRealEfficiency.unsupported(UNAVAILABLE, "실 효율을 계산하지 못했습니다.");
    }
  }

  /** 원본 효율은 이미 % 둘째 자리 내림 값(⌊…⌋ ÷ 100)이다 — 그 double의 짧은 십진 표기 그대로. */
  private static BigDecimal percent(Double value) {
    return value == null || !Double.isFinite(value) ? null : BigDecimal.valueOf(value);
  }

  private static Integer level(double value) {
    return Double.isFinite(value) ? (int) Math.round(value) : null;
  }
}
