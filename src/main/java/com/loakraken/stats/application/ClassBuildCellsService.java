package com.loakraken.stats.application;

import com.loakraken.character.application.CharacterBuildSourceService;
import com.loakraken.stats.domain.ClassBuilds;
import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 직업 빌드 통계에서 캐릭터 한 명의 칸(M26 2단계). 캐릭터 모듈의 캐시·저장 요약을 집계와 같은 변환({@link ClassBuildSampleReader})과 같은 키
 * 함수({@link ClassBuilds#cellsOf})로 바꾼다. 공식 API를 부르지 않는다.
 *
 * <p>inSample은 그 캐릭터 값이 이 조건(직업·아이템 레벨 범위·최근 확인 기간·전투력 관측)에 맞는지다. 실제 집계 포함 여부(처리정지 등)는 드러내지
 * 않는다(B5): 처리정지 캐릭터도 같은 값이면 같은 응답이다.
 */
@Service
public class ClassBuildCellsService {
  private final CharacterBuildSourceService sources;
  private final Clock clock;

  public ClassBuildCellsService(CharacterBuildSourceService sources, Clock clock) {
    this.sources = sources;
    this.clock = clock;
  }

  /** 조건에 맞지 않는 까닭. 여러 개일 수 있다. */
  public enum Reason {
    OTHER_CLASS,
    ITEM_LEVEL_OUT_OF_RANGE,
    NOT_RECENT,
    NO_COMBAT_POWER
  }

  public record Result(
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      Instant observedAt,
      CharacterBuildSourceService.Origin source,
      boolean inSample,
      List<Reason> reasons,
      BigDecimal itemLevelMin,
      BigDecimal itemLevelMax,
      int activeDays,
      BigDecimal braceletItemLevelMin,
      ClassBuilds.CharacterCells cells) {}

  /**
   * @param itemLevelMin null이면 빌드 통계 기본값(1680, 단 itemLevelMax만 1680 미만이면 1640)
   * @param braceletItemLevelMin null이면 1680, itemLevelMin보다 낮으면 itemLevelMin(빌드 통계와 같음)
   */
  public Result cells(
      String className,
      String characterName,
      BigDecimal itemLevelMin,
      BigDecimal itemLevelMax,
      Integer activeDays,
      BigDecimal braceletItemLevelMin) {
    var conditions =
        new StatsConditions(
            null,
            null,
            className,
            activeDays == null ? StatsRules.DEFAULT_ACTIVE_DAYS : activeDays,
            itemLevelMin,
            itemLevelMax,
            null,
            null,
            false);
    if (itemLevelMin == null
        && (itemLevelMax == null
            || itemLevelMax.compareTo(ClassBuilds.DEFAULT_ITEM_LEVEL_MIN) >= 0))
      conditions = conditions.withItemLevelMin(ClassBuilds.DEFAULT_ITEM_LEVEL_MIN);
    var bracelet =
        StatsConditions.strip(
            braceletItemLevelMin == null
                ? ClassBuilds.DEFAULT_BRACELET_ITEM_LEVEL_MIN
                : braceletItemLevelMin);
    if (bracelet.compareTo(conditions.itemLevelMin()) < 0) bracelet = conditions.itemLevelMin();

    var source = sources.find(characterName);
    var sample =
        ClassBuildSampleReader.fromFeatures(
            source.itemLevel(), source.arkPassiveTitle(), source.featuresJson());
    var reasons = new ArrayList<Reason>();
    if (!className.equals(source.className())) reasons.add(Reason.OTHER_CLASS);
    var level = source.itemLevel();
    if (level == null
        || level.compareTo(conditions.itemLevelMin()) < 0
        || (conditions.itemLevelMax() != null && level.compareTo(conditions.itemLevelMax()) > 0))
      reasons.add(Reason.ITEM_LEVEL_OUT_OF_RANGE);
    var since = clock.instant().minus(Duration.ofDays(conditions.activeDays()));
    if (source.observedAt() == null || source.observedAt().isBefore(since))
      reasons.add(Reason.NOT_RECENT);
    if (source.combatPower() == null) reasons.add(Reason.NO_COMBAT_POWER);
    return new Result(
        source.characterName(),
        source.serverName(),
        source.className(),
        source.itemLevel(),
        source.observedAt(),
        source.origin(),
        reasons.isEmpty(),
        List.copyOf(reasons),
        conditions.itemLevelMin(),
        conditions.itemLevelMax(),
        conditions.activeDays(),
        bracelet,
        ClassBuilds.cellsOf(sample, bracelet));
  }
}
