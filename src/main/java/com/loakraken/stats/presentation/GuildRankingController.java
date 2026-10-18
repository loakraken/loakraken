package com.loakraken.stats.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.stats.application.StatsPort.GuildSort;
import com.loakraken.stats.application.StatsQueryService;
import com.loakraken.stats.domain.StatsConditions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 길드 랭킹(#301, 볼트 「랭킹 확장 설계 초안」 v1 3절). 길드 탭·인기 길드(ranking.guilds.enabled)와 따로 켠다 — 기본
 * 꺼짐(ranking.guilds.list.enabled, #581). 공식 API를 부르지 않고 관측 DB에서 집계해 조건별로 10분 캐시한다.
 */
@ConditionalOnProperty(prefix = "ranking.guilds.list", name = "enabled", havingValue = "true")
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class GuildRankingController {
  private final StatsQueryService stats;

  public GuildRankingController(StatsQueryService stats) {
    this.stats = stats;
  }

  @GetMapping(value = "/api/v1/rankings/guilds", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "길드 랭킹",
      description =
          "같은 서버·같은 길드 이름(공식 프로필 GuildName)의 관측 캐릭터를 묶은 순위입니다. activeDays 안에 프로필을 관측한 공개 캐릭터만 넣으므로 값은 실제 길드 전체가 아니라 확인한 길드원 기준입니다. "
              + "sort: AVG(아이템 레벨 평균, 기본)·SUM(아이템 레벨 합)·CP_AVG(combatPowerAvgTop — 아이템 레벨 1750 이상 길드원 전투력 평균)·CP_SUM(combatPowerTotal — 전투력 총합, 전원). 그 값이 null인 길드(1750 이상 전투력 관측 0명, 또는 관측 0명)는 맨 뒤이며 순위는 공동 마지막(같은 rank)입니다. 확인한 길드원이 minMembers(5명) 미만인 길드는 빼고 excludedGuilds에 수를 줍니다. "
              + "guild(이름 일부)를 주면 그 이름에 맞는 줄만 주고 rank는 전체 순위 그대로입니다(맞는 줄이 없으면 rows=[]). "
              + "처리정지 목록는 캐릭터만 빼고 길드는 빼지 않습니다. 값이 같으면(소수 둘째 자리) 같은 순위입니다. 모집단이 30 미만이면 sampleStatus=COLLECTING이지만 값·순위는 그대로 줍니다(참고값). "
              + "400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<GuildRankingResponse> guilds(
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "최근 며칠 안에 프로필을 관측한 캐릭터(7·30·90, 기본 30)")
          @RequestParam(required = false)
          Integer activeDays,
      @Parameter(description = "길드 이름 일부(1~12자, 글자·숫자만, 대소문자 무시). 결과 줄의 rank는 전체 순위 그대로")
          @RequestParam(required = false)
          @Size(min = 1, max = 12)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String guild,
      @Parameter(description = "AVG(기본)·SUM·CP_AVG·CP_SUM")
          @RequestParam(required = false)
          @Pattern(regexp = "AVG|SUM|CP_AVG|CP_SUM")
          String sort,
      @Parameter(description = "1부터") @RequestParam(required = false) @Min(1) @Max(201)
          Integer page,
      @Parameter(description = "페이지 크기(기본 50, 최대 100)")
          @RequestParam(required = false)
          @Min(1)
          @Max(100)
          Integer size) {
    var conditions =
        new StatsConditions(
            server, null, null, ActiveDays.of(activeDays), null, null, null, null, false);
    return ApiResponse.of(
        StatsSuccessCode.GUILD_RANKING_SUCCESS,
        GuildRankingResponse.from(
            stats.guildRanking(
                conditions,
                sort == null ? GuildSort.AVG : GuildSort.valueOf(sort),
                page == null ? 1 : page,
                size == null ? 50 : size,
                guild)));
  }

  public record GuildRankingResponse(
      StatsResponses.StatsMetaResponse meta,
      @Schema(description = "AVG·SUM·CP_AVG·CP_SUM(문자열, 값이 늘 수 있음)", example = "AVG") String sort,
      int page,
      int size,
      @Schema(description = "순위에 넣는 최소 확인 길드원 수", example = "5") int minMembers,
      @Schema(description = "확인한 길드원이 minMembers 미만이라 뺀 길드 수") long excludedGuilds,
      @Schema(
              description =
                  "guild를 줬을 때 그 이름에 맞지만 확인한 길드원이 minMembers 미만이라 뺀 길드 수. guild가 없으면 null")
          @NullableResponse
          Long excludedMatches,
      @Schema(
              description =
                  "guild를 줬을 때 그 이름에 맞고 순위에 든 길드 수(쪽 수는 이 값으로 셉니다). guild가 없으면 null이고 쪽 수는 meta.population으로 셉니다")
          @NullableResponse
          Long matchCount,
      List<GuildRankingRowResponse> rows) {
    static GuildRankingResponse from(StatsQueryService.GuildRankingResult result) {
      return new GuildRankingResponse(
          StatsResponses.StatsMetaResponse.from(result.meta(), "GUILD"),
          result.sort(),
          result.page(),
          result.size(),
          result.minMembers(),
          result.excludedGuilds(),
          result.excludedMatches(),
          result.matchCount(),
          result.rows().stream()
              .map(
                  row ->
                      new GuildRankingRowResponse(
                          row.rank(),
                          row.serverName(),
                          row.guildName(),
                          row.memberCount(),
                          row.itemLevelAvg(),
                          row.itemLevelSum(),
                          row.combatPowerAvg(),
                          row.combatPowerCount(),
                          row.combatPowerAvgTop(),
                          row.combatPowerTopCount(),
                          row.combatPowerTotal()))
              .toList());
    }
  }

  public record GuildRankingRowResponse(
      @Schema(description = "같은 값(소수 둘째 자리)은 같은 순위") long rank,
      String serverName,
      @Schema(description = "공식 프로필 길드 이름") String guildName,
      @Schema(description = "확인한 공개 길드원 수(실제 길드 인원이 아님)", example = "12") long memberCount,
      @Schema(description = "확인한 길드원 아이템 레벨 평균, 소수 둘째 자리", example = "1702.35")
          BigDecimal itemLevelAvg,
      @Schema(description = "확인한 길드원 아이템 레벨 합, 소수 둘째 자리") BigDecimal itemLevelSum,
      @Schema(
              description =
                  "전투력을 관측한 길드원 전원의 전투력 평균(호환용, 부캐 포함, 소수 둘째 자리, 관측 0명이면 null). 화면의 평균은 combatPowerAvgTop이다")
          @NullableResponse
          BigDecimal combatPowerAvg,
      @Schema(description = "combatPowerAvg에 쓴 길드원 수(전투력 관측 전원, 호환용)") long combatPowerCount,
      @Schema(
              description =
                  "화면의 평균: 아이템 레벨 1750 이상이면서 전투력을 관측한 길드원의 전투력 평균(소수 둘째 자리, 길드 탭 summary.combatPowerAvgTop과 같은 정의). 그런 길드원이 0명이면 null(화면 '1750 이상 없음', CP_AVG 정렬 맨 뒤)",
              example = "5873.12")
          @NullableResponse
          BigDecimal combatPowerAvgTop,
      @Schema(description = "combatPowerAvgTop에 쓴 길드원 수(1750 이상·전투력 관측)", example = "6")
          long combatPowerTopCount,
      @Schema(
              description =
                  "전투력을 관측한 길드원 전원(부캐 포함)의 전투력 합(소수 둘째 자리, 길드 탭 summary.combatPowerTotal과 같은 정의). 관측 0명이면 null")
          @NullableResponse
          BigDecimal combatPowerTotal) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "ranking.guilds.list", name = "enabled", havingValue = "true")
  static class GuildRankingOpenApiConfiguration {
    @Bean
    OpenApiCustomizer guildRankingResponseContract() {
      return document ->
          ResponseContracts.apply(
              GuildRankingResponse.class, document.getComponents().getSchemas());
    }
  }
}
