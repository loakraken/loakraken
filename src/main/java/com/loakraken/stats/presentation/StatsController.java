package com.loakraken.stats.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.stats.application.StatsPort;
import com.loakraken.stats.application.StatsQueryService;
import com.loakraken.stats.domain.StatsRules.Metric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(
    name = "통계·랭킹",
    description =
        "LOAKraken이 관측·수집한 캐릭터로 만든 통계와 랭킹입니다(stats-v1). 게임 전체 인구가 아닙니다. 공식 API를 부르지 않고 조건별로 10분 캐시합니다.")
public class StatsController {
  private static final String ERRORS =
      "400 INVALID_REQUEST·STATS_INVALID_RANGE·STATS_TOO_MANY_BINS, 503 STATS_UNAVAILABLE";
  private final StatsQueryService stats;

  public StatsController(StatsQueryService stats) {
    this.stats = stats;
  }

  @GetMapping(value = "/api/v1/stats/summary", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "통계 요약",
      description = "표본 수, 평균·중앙값 아이템 레벨, 전투력 중앙값·상위 10% 기준선, 딜러:서포터, 4티어 보석 평균 레벨. " + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsSummaryResponse> summary(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter) {
    return ApiResponse.of(
        StatsSuccessCode.STATS_SUMMARY_SUCCESS,
        StatsResponses.StatsSummaryResponse.from(stats.summary(filter.toConditions())));
  }

  @GetMapping(value = "/api/v1/stats/classes", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "직업별 통계",
      description = "직업별 인원·비율, 서포터 비율(겸용 직업), 평균 레벨, 보석, 전투력 분위수(p10~p90). 인원 많은 순. " + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsClassesResponse> classes(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter) {
    return ApiResponse.of(
        StatsSuccessCode.STATS_CLASSES_SUCCESS,
        StatsResponses.StatsClassesResponse.from(stats.classes(filter.toConditions())));
  }

  @GetMapping(
      value = "/api/v1/stats/best-combat-power",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "최고 전투력 통계",
      description =
          "캐릭터마다 관측 이력 전체에서 가장 높았던 전투력(랭킹 bestCombatPower와 같음)의 전체·직업별 인원·평균·최고·분위수(p10~p90, p50 = 중앙값)입니다. "
              + "모집단은 다른 통계와 같고(처리정지 캐릭터 없음) 그 가운데 최고 전투력이 있는 캐릭터만 셉니다. combatPowerMin·Max 필터는 지금 전투력에 걸립니다. "
              + "표본 30 미만인 칸(전체 포함)은 mean·max·percentiles가 null이고 count·sampleStatus만 있습니다. "
              + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsBestCombatPowerResponse> bestCombatPower(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter) {
    return ApiResponse.of(
        StatsSuccessCode.STATS_BEST_COMBAT_POWER_SUCCESS,
        StatsResponses.StatsBestCombatPowerResponse.from(
            stats.bestCombatPower(filter.toConditions())));
  }

  @GetMapping(value = "/api/v1/stats/servers", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "서버별 통계", description = "서버별 인원·비율, 아이템 레벨·전투력 중앙값. " + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsServersResponse> servers(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter) {
    return ApiResponse.of(
        StatsSuccessCode.STATS_SERVERS_SUCCESS,
        StatsResponses.StatsServersResponse.from(stats.servers(filter.toConditions())));
  }

  @GetMapping(value = "/api/v1/stats/distribution", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "분포와 백분위",
      description =
          "아이템 레벨 또는 전투력 히스토그램. 기본 구간 폭은 아이템 레벨 10, 전투력 100이며 구간은 최대 300개입니다. percentileOf를 주면 그 값이 상위 몇 %인지 함께 줍니다. "
              + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsDistributionResponse> distribution(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter,
      @Parameter(description = "COMBAT_POWER 또는 ITEM_LEVEL") @RequestParam Metric metric,
      @Parameter(description = "구간 폭")
          @RequestParam(required = false)
          @DecimalMin("0.01")
          @DecimalMax("100000")
          BigDecimal width,
      @Parameter(description = "백분위를 알고 싶은 값(예: 내 전투력)")
          @RequestParam(required = false)
          @DecimalMin("0")
          @DecimalMax("100000")
          BigDecimal percentileOf) {
    return ApiResponse.of(
        StatsSuccessCode.STATS_DISTRIBUTION_SUCCESS,
        StatsResponses.StatsDistributionResponse.from(
            stats.distribution(filter.toConditions(), metric, width, percentileOf)));
  }

  @GetMapping(value = "/api/v1/stats/snapshots", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "기준일 목록",
      description = "현재는 latest(마지막 집계)만 제공합니다. 일별 06:00 KST 스냅샷은 다음 단계입니다. 503 STATS_UNAVAILABLE")
  @StandardErrors
  public ApiResponse<StatsResponses.StatsSnapshotsResponse> snapshots() {
    return ApiResponse.of(
        StatsSuccessCode.STATS_SNAPSHOTS_SUCCESS,
        StatsResponses.StatsSnapshotsResponse.from(stats.snapshots()));
  }

  @GetMapping(value = "/api/v1/rankings", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "랭킹",
      description =
          "전투력 또는 아이템 레벨 순위. 서버·직업·역할 등 필터로 범위를 정합니다. 동률은 같은 순위입니다. 50개 단위 페이지, 최대 10,000위까지. "
              + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsRankingResponse> rankings(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter,
      @Parameter(description = "COMBAT_POWER(기본) 또는 ITEM_LEVEL") @RequestParam(required = false)
          Metric metric,
      @Parameter(description = "1부터") @RequestParam(required = false) @Min(1) @Max(201)
          Integer page,
      @Parameter(description = "페이지 크기(기본 50, 최대 100)")
          @RequestParam(required = false)
          @Min(1)
          @Max(100)
          Integer size) {
    return ApiResponse.of(
        StatsSuccessCode.RANKING_SUCCESS,
        StatsResponses.StatsRankingResponse.from(
            stats.ranking(
                filter.toConditions(),
                metric == null ? Metric.COMBAT_POWER : metric,
                page == null ? 1 : page,
                size == null ? 50 : size)));
  }

  @GetMapping(value = "/api/v1/rankings/bracelets", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "팔찌 효율 랭킹",
      description =
          "팔찌 효율 %(지금 전투력이 팔찌를 뺀 전투력보다 몇 % 높은지) 순위. 캐릭터 검색으로 장비를 관측한 딜러의 계산된 값만, 지금 계산 규칙 버전 값만 셉니다. 필터·페이지는 전투력 랭킹과 같습니다. "
              + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsEfficiencyRankingResponse> braceletRanking(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter,
      @Parameter(description = "1부터") @RequestParam(required = false) @Min(1) @Max(201)
          Integer page,
      @Parameter(description = "페이지 크기(기본 50, 최대 100)")
          @RequestParam(required = false)
          @Min(1)
          @Max(100)
          Integer size) {
    return efficiency(filter, StatsPort.EfficiencyKind.BRACELET, page, size);
  }

  @GetMapping(value = "/api/v1/rankings/ark-grid-gems", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "젬 효율 랭킹",
      description =
          "아크 그리드 젬 옵션 합계 효율 %(지금 전투력이 젬 옵션을 모두 뺀 전투력보다 몇 % 높은지, 코어 제외) 순위. 캐릭터 검색으로 장비를 관측한 딜러의 계산된 값만, 지금 계산 규칙 버전 값만 셉니다. 필터·페이지는 전투력 랭킹과 같습니다. "
              + ERRORS)
  @StandardErrors
  public ApiResponse<StatsResponses.StatsEfficiencyRankingResponse> arkGridGemRanking(
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter,
      @Parameter(description = "1부터") @RequestParam(required = false) @Min(1) @Max(201)
          Integer page,
      @Parameter(description = "페이지 크기(기본 50, 최대 100)")
          @RequestParam(required = false)
          @Min(1)
          @Max(100)
          Integer size) {
    return efficiency(filter, StatsPort.EfficiencyKind.ARK_GRID_GEM, page, size);
  }

  private ApiResponse<StatsResponses.StatsEfficiencyRankingResponse> efficiency(
      StatsFilterRequest filter, StatsPort.EfficiencyKind kind, Integer page, Integer size) {
    return ApiResponse.of(
        StatsSuccessCode.RANKING_SUCCESS,
        StatsResponses.StatsEfficiencyRankingResponse.from(
            stats.efficiencyRanking(
                filter.toConditions(), kind, page == null ? 1 : page, size == null ? 50 : size)));
  }

  @GetMapping(value = "/api/v1/characters/rank", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "캐릭터 순위",
      description =
          "전투력·아이템 레벨 각각의 전체·서버·직업 순위와 상위 %, 티어(TOP1|TOP5|TOP10|TOP25|TOP50|REST). LOAKraken에 기록된 값 기준이라 캐릭터를 먼저 검색하면 최신 값으로 계산됩니다. "
              + "404 STATS_CHARACTER_NOT_OBSERVED, "
              + ERRORS)
  @StandardErrors
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "STATS_CHARACTER_NOT_OBSERVED",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  public ApiResponse<StatsResponses.StatsCharacterRankResponse> characterRank(
      @Parameter(description = "캐릭터명")
          @RequestParam
          @NotBlank
          @Size(max = 64)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String characterName,
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter) {
    return ApiResponse.of(
        StatsSuccessCode.CHARACTER_RANK_SUCCESS,
        StatsResponses.StatsCharacterRankResponse.from(
            stats.characterRank(characterName.strip(), filter.toConditions())));
  }

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST, STATS_INVALID_RANGE 또는 STATS_TOO_MANY_BINS",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "STATS_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  @interface StandardErrors {}
}
