package com.loakraken.stats.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.stats.application.StatsPort;
import com.loakraken.stats.application.StatsQueryService;
import com.loakraken.stats.application.StatsQueryService.Meta;
import com.loakraken.stats.domain.StatsConditions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 통계·랭킹 응답. 표본이 30명 미만(sampleStatus=COLLECTING)이면 분위수·백분위·티어는 null이고, 평균·중앙값·서포터 비율·보석 평균은 표본 1명
 * 이상이면 참고값으로 줍니다(#273).
 */
public final class StatsResponses {
  private StatsResponses() {}

  @Schema(
      description =
          "공통 메타. 모집단은 LOAKraken이 관측한 캐릭터 중 최근 activeDays일 안에 프로필(전투력)을 확인한 캐릭터이며 게임 전체 인구가 아닙니다.")
  public record StatsMetaResponse(
      @Schema(description = "조건에 맞는 캐릭터 수") long population,
      StatsConditionsResponse conditions,
      Instant aggregatedAt,
      @Schema(description = "현재는 latest만 제공합니다", example = "latest") String snapshot,
      @Schema(example = "stats-v1") String ruleVersion,
      @Schema(example = "30") int minSample,
      @Schema(
              description = "COLLECTING(표본 30명 미만) 또는 READY",
              allowableValues = {"COLLECTING", "READY"})
          String sampleStatus,
      StatsCoverageResponse coverage,
      @Schema(
              description =
                  "population을 세는 단위: CHARACTER(캐릭터), ROSTER(원정대), GUILD(길드). 문자열이며 값이 늘 수 있음",
              example = "CHARACTER")
          String populationUnit) {
    static StatsMetaResponse from(Meta meta) {
      return from(meta, "CHARACTER");
    }

    static StatsMetaResponse from(Meta meta, String populationUnit) {
      return new StatsMetaResponse(
          meta.population(),
          StatsConditionsResponse.from(meta.conditions()),
          meta.aggregatedAt(),
          meta.snapshot(),
          meta.ruleVersion(),
          meta.minSample(),
          meta.sampleStatus().name(),
          StatsCoverageResponse.from(meta.coverage()),
          populationUnit);
    }
  }

  @Schema(description = "정규화한 조건(기본값 포함)")
  public record StatsConditionsResponse(
      @NullableResponse String server,
      @NullableResponse @Schema(allowableValues = {"DEALER", "SUPPORT"}) String role,
      @NullableResponse String className,
      @Schema(description = "최근 확인 기간(일): 7, 30, 90") int activeDays,
      BigDecimal itemLevelMin,
      @NullableResponse BigDecimal itemLevelMax,
      @NullableResponse BigDecimal combatPowerMin,
      @NullableResponse BigDecimal combatPowerMax,
      @Schema(description = "원정대(같은 서버) 대표 캐릭터(아이템 레벨 최고)만") boolean representativeOnly) {
    static StatsConditionsResponse from(StatsConditions conditions) {
      return new StatsConditionsResponse(
          conditions.server(),
          conditions.role() == null ? null : conditions.role().name(),
          conditions.className(),
          conditions.activeDays(),
          conditions.itemLevelMin(),
          conditions.itemLevelMax(),
          conditions.combatPowerMin(),
          conditions.combatPowerMax(),
          conditions.representativeOnly());
    }
  }

  @Schema(description = "수집 범위. 표본이 어디까지 모였는지 보여 줍니다.")
  public record StatsCoverageResponse(
      @Schema(description = "프로필(전투력)을 확인한 캐릭터 수") long observedCharacters,
      @Schema(description = "원정대로 발견한 캐릭터 포함 전체") long knownCharacters,
      @Schema(description = "원정대를 훑은 계정 수") long discoveredAccounts) {
    static StatsCoverageResponse from(StatsPort.Coverage coverage) {
      return new StatsCoverageResponse(
          coverage.observedCharacters(), coverage.knownCharacters(), coverage.discoveredAccounts());
    }
  }

  public record StatsSummaryResponse(
      StatsMetaResponse meta,
      @NullableResponse BigDecimal itemLevelMean,
      @NullableResponse BigDecimal itemLevelMedian,
      @NullableResponse BigDecimal combatPowerMedian,
      @NullableResponse @Schema(description = "전투력 상위 10% 기준선(90번째 백분위수)")
          BigDecimal combatPowerP90,
      long dealers,
      long supports,
      @Schema(description = "역할을 판정하지 못한 캐릭터 수") long unknownRole,
      @NullableResponse @Schema(description = "역할 판정 캐릭터 중 서포터 비율(%)") BigDecimal supportShare,
      @NullableResponse
          @Schema(description = "4티어 보석(겁화·작열·광휘) 캐릭터별 평균 레벨의 평균. 보석 기록이 없으면 null(30명 미만이면 참고값)")
          BigDecimal gemMeanLevel,
      @Schema(description = "보석 평균을 계산한 캐릭터 수") long gemSample) {
    static StatsSummaryResponse from(StatsQueryService.SummaryResult result) {
      return new StatsSummaryResponse(
          StatsMetaResponse.from(result.meta()),
          result.itemLevelMean(),
          result.itemLevelMedian(),
          result.combatPowerMedian(),
          result.combatPowerP90(),
          result.dealers(),
          result.supports(),
          result.unknownRole(),
          result.supportShare(),
          result.gemMeanLevel(),
          result.gemSample());
    }
  }

  public record StatsPercentilesResponse(
      BigDecimal p10, BigDecimal p25, BigDecimal p50, BigDecimal p75, BigDecimal p90) {}

  public record StatsClassRowResponse(
      String className,
      long count,
      @NullableResponse @Schema(description = "모집단 대비 비율(%)") BigDecimal share,
      long dealers,
      long supports,
      long unknownRole,
      @NullableResponse
          @Schema(description = "딜러·서포터 겸용 직업(바드·홀리나이트·도화가·발키리)만. 역할 판정 캐릭터 중 서포터 비율(%)")
          BigDecimal supportShare,
      @NullableResponse BigDecimal itemLevelMean,
      @NullableResponse BigDecimal gemMeanLevel,
      @NullableResponse @Schema(description = "전투력 분위수") StatsPercentilesResponse combatPower,
      @Schema(allowableValues = {"COLLECTING", "READY"}) String sampleStatus) {}

  @Schema(
      description =
          "최고 전투력은 그 캐릭터의 관측 이력 전체에서 가장 높았던 전투력입니다(랭킹 bestCombatPower와 같음). 표본 30 미만이면 mean·max·percentiles는 null입니다")
  public record StatsBestCombatPowerRowResponse(
      long count,
      @NullableResponse @Schema(description = "평균, 소수 둘째 자리") BigDecimal mean,
      @NullableResponse @Schema(description = "최고값") BigDecimal max,
      @NullableResponse @Schema(description = "분위수(p50 = 중앙값)")
          StatsPercentilesResponse percentiles,
      @Schema(allowableValues = {"COLLECTING", "READY"}) String sampleStatus) {
    static StatsBestCombatPowerRowResponse from(StatsQueryService.BestCombatPowerStat row) {
      return new StatsBestCombatPowerRowResponse(
          row.count(),
          row.mean(),
          row.max(),
          row.percentiles() == null
              ? null
              : new StatsPercentilesResponse(
                  row.percentiles().p10(),
                  row.percentiles().p25(),
                  row.percentiles().p50(),
                  row.percentiles().p75(),
                  row.percentiles().p90()),
          row.sampleStatus().name());
    }
  }

  public record StatsBestCombatPowerClassResponse(
      String className, StatsBestCombatPowerRowResponse value) {}

  @Schema(
      description =
          "meta.population은 최고 전투력이 있는 캐릭터 수입니다. 필터의 combatPowerMin·Max는 최고 전투력이 아니라 지금 전투력에 걸립니다")
  public record StatsBestCombatPowerResponse(
      StatsMetaResponse meta,
      StatsBestCombatPowerRowResponse overall,
      @Schema(description = "직업별, 인원 많은 순(같으면 직업 이름순)")
          List<StatsBestCombatPowerClassResponse> classes) {
    static StatsBestCombatPowerResponse from(StatsQueryService.BestCombatPowerResult result) {
      return new StatsBestCombatPowerResponse(
          StatsMetaResponse.from(result.meta()),
          StatsBestCombatPowerRowResponse.from(result.overall()),
          result.classes().stream()
              .map(
                  row ->
                      new StatsBestCombatPowerClassResponse(
                          row.className(), StatsBestCombatPowerRowResponse.from(row)))
              .toList());
    }
  }

  public record StatsClassesResponse(StatsMetaResponse meta, List<StatsClassRowResponse> rows) {
    static StatsClassesResponse from(StatsQueryService.ClassesResult result) {
      return new StatsClassesResponse(
          StatsMetaResponse.from(result.meta()),
          result.rows().stream().map(StatsClassesResponse::row).toList());
    }

    static StatsClassRowResponse row(StatsQueryService.ClassStat row) {
      return new StatsClassRowResponse(
          row.className(),
          row.count(),
          row.share(),
          row.dealers(),
          row.supports(),
          row.unknownRole(),
          row.supportShare(),
          row.itemLevelMean(),
          row.gemMeanLevel(),
          row.combatPower() == null
              ? null
              : new StatsPercentilesResponse(
                  row.combatPower().p10(),
                  row.combatPower().p25(),
                  row.combatPower().p50(),
                  row.combatPower().p75(),
                  row.combatPower().p90()),
          row.sampleStatus().name());
    }
  }

  public record StatsClassEngravingRowResponse(
      @Schema(description = "직업 각인(공식 아크 패시브 칭호 원문, 랭킹 직업 각인 필터와 같은 표기)") String classEngraving,
      @Schema(description = "직업별 통계와 같은 값(share는 같은 모집단 대비)") StatsClassRowResponse stats) {}

  public record StatsClassCountResponse(String className, long count) {}

  public record StatsClassEngravingTableResponse(
      StatsMetaResponse meta,
      @Schema(description = "직업 각인별, 인원 많은 순(같으면 직업·직업 각인 이름순)")
          List<StatsClassEngravingRowResponse> rows,
      @Schema(description = "직업별 직업 각인 미확인 수(장비를 관측하지 않았거나 직업 각인이 없는 캐릭터). 행에는 들어가지 않습니다")
          List<StatsClassCountResponse> unknownByClass) {
    static StatsClassEngravingTableResponse from(
        StatsQueryService.ClassEngravingTableResult result) {
      return new StatsClassEngravingTableResponse(
          StatsMetaResponse.from(result.meta()),
          result.rows().stream()
              .map(
                  row ->
                      new StatsClassEngravingRowResponse(
                          row.classEngraving(), StatsClassesResponse.row(row.stats())))
              .toList(),
          result.unknownByClass().stream()
              .map(count -> new StatsClassCountResponse(count.className(), count.count()))
              .toList());
    }
  }

  public record StatsServerRowResponse(
      String serverName,
      long count,
      @NullableResponse BigDecimal share,
      @NullableResponse BigDecimal itemLevelMedian,
      @NullableResponse BigDecimal combatPowerMedian,
      @Schema(description = "필터와 무관하게 이 서버에서 알고 있는 전체 캐릭터 수(원정대로 발견한 캐릭터 포함)") long knownCharacters,
      @Schema(description = "이 서버에서 수집한 원정대 수(roster_key 기준)") long rosters,
      @Schema(allowableValues = {"COLLECTING", "READY"}) String sampleStatus) {}

  public record StatsServersResponse(StatsMetaResponse meta, List<StatsServerRowResponse> rows) {
    static StatsServersResponse from(StatsQueryService.ServersResult result) {
      return new StatsServersResponse(
          StatsMetaResponse.from(result.meta()),
          result.rows().stream()
              .map(
                  row ->
                      new StatsServerRowResponse(
                          row.serverName(),
                          row.count(),
                          row.share(),
                          row.itemLevelMedian(),
                          row.combatPowerMedian(),
                          row.knownCharacters(),
                          row.rosters(),
                          row.sampleStatus().name()))
              .toList());
    }
  }

  @Schema(description = "구간 [from, to)")
  public record StatsBinResponse(BigDecimal from, BigDecimal to, long count) {}

  public record StatsPercentileOfResponse(
      BigDecimal value,
      @Schema(description = "이 값보다 큰 캐릭터 수 + 1") long rank,
      long total,
      @NullableResponse @Schema(description = "상위 몇 %인지(소수 둘째 자리 올림)") BigDecimal topPercent,
      @NullableResponse
          @Schema(allowableValues = {"TOP1", "TOP5", "TOP10", "TOP25", "TOP50", "REST"})
          String tier) {}

  public record StatsDistributionResponse(
      StatsMetaResponse meta,
      @Schema(allowableValues = {"COMBAT_POWER", "ITEM_LEVEL"}) String metric,
      BigDecimal width,
      @Schema(description = "관측값이 있는 구간만(오름차순). 빈 구간은 생략합니다") List<StatsBinResponse> bins,
      @NullableResponse @Schema(description = "percentileOf를 줬을 때만")
          StatsPercentileOfResponse percentileOf) {
    static StatsDistributionResponse from(StatsQueryService.DistributionResult result) {
      var of = result.percentileOf();
      return new StatsDistributionResponse(
          StatsMetaResponse.from(result.meta()),
          result.metric().name(),
          result.width(),
          result.bins().stream()
              .map(bin -> new StatsBinResponse(bin.from(), bin.to(), bin.count()))
              .toList(),
          of == null
              ? null
              : new StatsPercentileOfResponse(
                  of.value(),
                  of.rank(),
                  of.total(),
                  of.topPercent(),
                  of.tier() == null ? null : of.tier().name()));
    }
  }

  public record StatsSnapshotResponse(String snapshot, Instant aggregatedAt, long population) {}

  public record StatsSnapshotsResponse(List<StatsSnapshotResponse> snapshots) {
    static StatsSnapshotsResponse from(StatsQueryService.SnapshotsResult result) {
      return new StatsSnapshotsResponse(
          result.snapshots().stream()
              .map(
                  snapshot ->
                      new StatsSnapshotResponse(
                          snapshot.snapshot(), snapshot.aggregatedAt(), snapshot.population()))
              .toList());
    }
  }

  public record StatsRankedCharacterResponse(
      @Schema(description = "동률은 같은 순위(1, 1, 3)") long rank,
      String characterName,
      String serverName,
      String className,
      @NullableResponse @Schema(allowableValues = {"DEALER", "SUPPORT"}) String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      @NullableResponse BigDecimal topPercent,
      @NullableResponse
          @Schema(allowableValues = {"TOP1", "TOP5", "TOP10", "TOP25", "TOP50", "REST"})
          String tier,
      @NullableResponse @Schema(description = "관측한 최고 전투력(LOAKraken이 기록한 값 중 최고)")
          BigDecimal bestCombatPower,
      @NullableResponse
          @Schema(description = "아크 패시브 진화·깨달음·도약 포인트(마지막으로 관측한 장비 기준). 관측하지 않았으면 null")
          StatsArkPassivePointsResponse arkPassivePoints,
      @NullableResponse @Schema(description = "직업 각인(깨달음 직업 노드, 공식 아크 패시브 칭호). 관측하지 않았으면 null")
          String classEngraving,
      @NullableResponse
          @Schema(description = "에스더 무기 강화·엘라 부여 단계(마지막으로 관측한 장비 기준). 에스더 무기가 아니거나 관측하지 않았으면 null")
          StatsEstherWeaponResponse estherWeapon) {}

  public record StatsEstherWeaponResponse(
      @NullableResponse @Schema(description = "강화 단계(이름 앞 +N)") Integer refineLevel,
      @NullableResponse @Schema(description = "엘라 부여 단계") Integer ellaStage) {}

  public record StatsArkPassivePointsResponse(
      @NullableResponse Integer evolution,
      @NullableResponse Integer enlightenment,
      @NullableResponse Integer leap) {}

  /** 팔찌·젬 효율 랭킹(L38·L39). 두 경로가 같은 모양이다. */
  public record StatsEfficiencyRankingResponse(
      StatsMetaResponse meta,
      @Schema(
              description = "BRACELET(팔찌 효율) 또는 ARK_GRID_GEM(젬 효율)",
              allowableValues = {"BRACELET", "ARK_GRID_GEM"})
          String kind,
      @Schema(
              description = "순위에 쓴 계산 규칙 버전. 이 버전으로 계산된 값만 셉니다. 값의 정의도 이 버전으로 구분합니다(경로 설명)",
              example = "bracelet-real-v1")
          String ruleVersion,
      @Schema(
              description =
                  "순위 대상: 캐릭터 검색으로 장비를 관측해 효율을 계산한 캐릭터(딜러, 계산이 된 값만)입니다. 수집기만 거친 캐릭터는 들어가지 않습니다. 계산 모델이 바뀌면 다음 관측 때 갱신된 캐릭터부터 다시 셉니다.")
          String populationNote,
      int page,
      int size,
      @Schema(description = "효율 높은 순. 전체 순위 대상 수는 meta.population")
          List<StatsEfficiencyRankedCharacterResponse> rows) {
    static final String POPULATION = "캐릭터 검색으로 장비를 관측해 효율을 계산한 캐릭터만 순위에 들어갑니다.";

    static StatsEfficiencyRankingResponse from(StatsQueryService.EfficiencyRankingResult result) {
      return new StatsEfficiencyRankingResponse(
          StatsMetaResponse.from(result.meta()),
          result.kind().name(),
          result.ruleVersion(),
          POPULATION,
          result.page(),
          result.size(),
          result.rows().stream()
              .map(
                  row ->
                      new StatsEfficiencyRankedCharacterResponse(
                          row.rank(),
                          row.characterName(),
                          row.serverName(),
                          row.className(),
                          row.position(),
                          row.itemLevel(),
                          row.combatPower(),
                          row.value(),
                          row.observedAt(),
                          row.topPercent(),
                          row.tier() == null ? null : row.tier().name()))
              .toList());
    }
  }

  public record StatsEfficiencyRankedCharacterResponse(
      @Schema(description = "동률은 같은 순위(1, 1, 3)") long rank,
      String characterName,
      String serverName,
      String className,
      @NullableResponse @Schema(allowableValues = {"DEALER", "SUPPORT"}) String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      @Schema(description = "효율 %(소수 둘째 자리). 정의는 ruleVersion에 따릅니다(경로 설명)") BigDecimal valuePercent,
      @Schema(description = "효율을 계산한 관측 시각") java.time.Instant observedAt,
      @NullableResponse BigDecimal topPercent,
      @NullableResponse
          @Schema(allowableValues = {"TOP1", "TOP5", "TOP10", "TOP25", "TOP50", "REST"})
          String tier) {}

  public record StatsRankingResponse(
      StatsMetaResponse meta,
      @Schema(allowableValues = {"COMBAT_POWER", "ITEM_LEVEL"}) String metric,
      int page,
      int size,
      @Schema(description = "전체 순위 대상 수는 meta.population") List<StatsRankedCharacterResponse> rows,
      @NullableResponse @Schema(description = "요청한 직업 각인(직업 각인 목록의 표기). 필터를 쓰지 않으면 null")
          String classEngraving,
      @NullableResponse
          @Schema(
              description =
                  "직업 각인 필터를 켰을 때, 같은 조건에서 직업 각인을 모르는(장비를 관측하지 않았거나 직업 각인이 없는(깨달음 노드 없음)) 캐릭터 수. 이 캐릭터들은 순위에 들어가지 않습니다. 필터를 쓰지 않거나 세지 못하면 null")
          Long classEngravingUnknown) {
    static StatsRankingResponse from(StatsQueryService.RankingResult result) {
      return new StatsRankingResponse(
          StatsMetaResponse.from(result.meta()),
          result.metric().name(),
          result.page(),
          result.size(),
          result.rows().stream()
              .map(
                  row ->
                      new StatsRankedCharacterResponse(
                          row.rank(),
                          row.characterName(),
                          row.serverName(),
                          row.className(),
                          row.position(),
                          row.itemLevel(),
                          row.combatPower(),
                          row.topPercent(),
                          row.tier() == null ? null : row.tier().name(),
                          row.bestCombatPower(),
                          row.arkPassive() == null
                                  || (row.arkPassive().evolution() == null
                                      && row.arkPassive().enlightenment() == null
                                      && row.arkPassive().leap() == null)
                              ? null
                              : new StatsArkPassivePointsResponse(
                                  row.arkPassive().evolution(),
                                  row.arkPassive().enlightenment(),
                                  row.arkPassive().leap()),
                          row.arkPassive() == null ? null : row.arkPassive().classEngraving(),
                          row.estherWeapon() == null
                              ? null
                              : new StatsEstherWeaponResponse(
                                  row.estherWeapon().refineLevel(),
                                  row.estherWeapon().ellaStage())))
              .toList(),
          result.classEngraving(),
          result.classEngravingUnknown());
    }
  }

  /** 랭킹 직업 각인 선택지(#618). */
  public record StatsClassEngravingsResponse(
      String className,
      @Schema(description = "직업 각인 목록 순서. 랭킹 classEngraving에 그대로 넣을 수 있는 값")
          List<StatsClassEngravingResponse> classEngravings,
      @NullableResponse
          @Schema(
              description =
                  "같은 조건에서 직업 각인을 모르는(장비를 관측하지 않았거나 직업 각인이 없는(깨달음 노드 없음)) 캐릭터 수. 세지 못하면 null")
          Long unknown,
      @NullableResponse @Schema(description = "수를 센 시각(10분 캐시). 세지 못하면 null")
          Instant aggregatedAt) {
    static StatsClassEngravingsResponse from(StatsQueryService.ClassEngravingsResult result) {
      return new StatsClassEngravingsResponse(
          result.className(),
          result.classEngravings().stream()
              .map(row -> new StatsClassEngravingResponse(row.name(), row.count()))
              .toList(),
          result.unknown(),
          result.aggregatedAt());
    }
  }

  public record StatsClassEngravingResponse(
      @Schema(description = "직업 각인(공식 아크 패시브 칭호 표기)") String name,
      @NullableResponse
          @Schema(description = "같은 조건 모집단에서 마지막으로 관측한 장비의 직업 각인이 이 값인 캐릭터 수. 세지 못하면 null")
          Long count) {}

  public record StatsRankEntryResponse(
      @Schema(allowableValues = {"COMBAT_POWER", "ITEM_LEVEL"}) String metric,
      @Schema(allowableValues = {"ALL", "SERVER", "CLASS", "SERVER_CLASS"}) String scope,
      @NullableResponse
          @Schema(description = "SERVER면 서버명, CLASS면 직업명, SERVER_CLASS면 \"서버/직업\". ALL이면 null")
          String scopeValue,
      long rank,
      long total,
      @NullableResponse BigDecimal topPercent,
      @NullableResponse
          @Schema(allowableValues = {"TOP1", "TOP5", "TOP10", "TOP25", "TOP50", "REST"})
          String tier,
      @Schema(allowableValues = {"COLLECTING", "READY"}) String sampleStatus,
      @NullableResponse
          @Schema(
              description =
                  "한 단계 높은 티어. 지금 TOP1이거나, 표본 30 미만(tier null)이거나, 인원이 적어 그 티어에 닿을 수 있는 순위가 없으면(⌊상한% × total / 100⌋ = 0) null",
              allowableValues = {"TOP1", "TOP5", "TOP10", "TOP25", "TOP50"})
          String nextTier,
      @NullableResponse
          @Schema(
              description =
                  "다음 티어 경계값: 나를 뺀 모집단에서 K번째(K = ⌊다음 티어 상한% × total / 100⌋)로 높은 값. 이 값과 같아지면 다음 티어가 됩니다. nextTier가 null이면 null")
          BigDecimal nextTierValue,
      @NullableResponse @Schema(description = "경계값 − 지금 값(0보다 큼). nextTier가 null이면 null")
          BigDecimal nextTierGap) {}

  public record StatsCharacterRankResponse(
      String characterName,
      String serverName,
      String className,
      @NullableResponse @Schema(allowableValues = {"DEALER", "SUPPORT"}) String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      @NullableResponse Instant refreshedAt,
      @Schema(
              description =
                  "순위 공개 여부. 지금은 늘 VISIBLE이다(HIDDEN은 더 이상 쓰지 않음). included와 entries의 sampleStatus로 판단한다",
              allowableValues = {"VISIBLE", "HIDDEN"})
          String rankVisibility,
      @Schema(
              description =
                  "조건(최근 확인 기간·레벨 범위 등)의 모집단에 들어가는가. false면 entries는 빈 배열. HIDDEN이면 항상 false")
          boolean included,
      StatsConditionsResponse conditions,
      Instant aggregatedAt,
      String ruleVersion,
      int minSample,
      List<StatsRankEntryResponse> entries) {
    static StatsCharacterRankResponse from(StatsQueryService.CharacterRankResult result) {
      return new StatsCharacterRankResponse(
          result.characterName(),
          result.serverName(),
          result.className(),
          result.position(),
          result.itemLevel(),
          result.combatPower(),
          result.refreshedAt(),
          result.rankVisibility().name(),
          result.included(),
          StatsConditionsResponse.from(result.conditions()),
          result.aggregatedAt(),
          result.ruleVersion(),
          result.minSample(),
          result.entries().stream()
              .map(
                  entry ->
                      new StatsRankEntryResponse(
                          entry.metric().name(),
                          entry.scope().name(),
                          entry.scopeValue(),
                          entry.rank(),
                          entry.total(),
                          entry.topPercent(),
                          entry.tier() == null ? null : entry.tier().name(),
                          entry.sampleStatus().name(),
                          entry.nextTier() == null ? null : entry.nextTier().name(),
                          entry.nextTierValue(),
                          entry.nextTierGap()))
              .toList());
    }
  }
}
