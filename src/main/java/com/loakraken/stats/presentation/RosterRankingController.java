package com.loakraken.stats.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.stats.application.StatsPort.RosterAggregate;
import com.loakraken.stats.application.StatsPort.RosterBasis;
import com.loakraken.stats.application.StatsPort.RosterMember;
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
 * 원정대 랭킹(#297, 볼트 「랭킹 확장 설계 초안」 v1 2절). 웹 화면이 준비될 때까지 기본 꺼짐(ranking.rosters.enabled). 공식 API를 부르지
 * 않고 관측 DB에서 집계해 조건별로 10분 캐시한다.
 */
@ConditionalOnProperty(prefix = "ranking.rosters", name = "enabled", havingValue = "true")
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class RosterRankingController {
  private final StatsQueryService stats;

  public RosterRankingController(StatsQueryService stats) {
    this.stats = stats;
  }

  @GetMapping(value = "/api/v1/rankings/rosters", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "원정대 랭킹",
      description =
          "같은 서버 원정대(공식 원정대 캐릭터 목록)를 묶어 기준·합산 값 순으로 매긴 순위입니다. activeDays 안에 관측한 공개 캐릭터만 넣고, 대표는 아이템 레벨이 가장 높은 공개 캐릭터입니다. "
              + "basis: ITEM_LEVEL(아이템 레벨), COMBAT_POWER(전투력, activeDays 안에 관측한 값만), ROSTER_LEVEL(원정대 레벨, aggregate는 쓰지 않음). "
              + "aggregate: TOP6_AVG(식스맨 평균 = 아이템 레벨 상위 6명 값의 합 ÷ 6), TOP6_SUM(같은 6명의 합), ALL_AVG(전체 평균). 상위 6명은 기준과 무관하게 한 서버 안에서 아이템 레벨 순(같으면 이름 순)으로 고르고(server가 없으면 대표 캐릭터의 서버), 원정대 인원이 6명보다 적어도 TOP6_AVG는 6으로 나눕니다(값이 있는 수는 usedCharacters). "
              + "COMBAT_POWER에서는 아이템 레벨 1640 이상인데 activeDays 안에 전투력을 관측하지 못한 캐릭터가 있는 원정대를 빼고 excludedRosters에 수를 줍니다. "
              + "값이 같으면(소수 둘째 자리) 같은 순위입니다. 모집단이 30 미만이면 sampleStatus=COLLECTING이지만 값·순위는 그대로 줍니다(참고값). "
              + "400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<RosterRankingResponse> rosters(
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "최근 며칠 안에 관측한 캐릭터(7·30·90, 기본 30)") @RequestParam(required = false)
          Integer activeDays,
      @Parameter(description = "ITEM_LEVEL(기본), COMBAT_POWER, ROSTER_LEVEL")
          @RequestParam(required = false)
          @Pattern(regexp = "ITEM_LEVEL|COMBAT_POWER|ROSTER_LEVEL")
          String basis,
      @Parameter(description = "TOP6_AVG(기본), TOP6_SUM, ALL_AVG")
          @RequestParam(required = false)
          @Pattern(regexp = "TOP6_AVG|TOP6_SUM|ALL_AVG")
          String aggregate,
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
        StatsSuccessCode.ROSTER_RANKING_SUCCESS,
        RosterRankingResponse.from(
            stats.rosterRanking(
                conditions,
                basis == null ? RosterBasis.ITEM_LEVEL : RosterBasis.valueOf(basis),
                aggregate == null ? RosterAggregate.TOP6_AVG : RosterAggregate.valueOf(aggregate),
                page == null ? 1 : page,
                size == null ? 50 : size)));
  }

  public record RosterRankingResponse(
      StatsResponses.StatsMetaResponse meta,
      @Schema(
              description = "ITEM_LEVEL·COMBAT_POWER·ROSTER_LEVEL(문자열, 값이 늘 수 있음)",
              example = "ITEM_LEVEL")
          String basis,
      @Schema(
              description = "TOP6_AVG·TOP6_SUM·ALL_AVG(문자열, 값이 늘 수 있음). basis=ROSTER_LEVEL이면 쓰지 않음",
              example = "TOP6_AVG")
          String aggregate,
      int page,
      int size,
      @Schema(description = "COMBAT_POWER에서 전투력 관측 누락으로 뺀 원정대 수(다른 기준은 0)") long excludedRosters,
      List<RosterRankingRowResponse> rows) {
    static RosterRankingResponse from(StatsQueryService.RosterRankingResult result) {
      return new RosterRankingResponse(
          StatsResponses.StatsMetaResponse.from(result.meta(), "ROSTER"),
          result.basis(),
          result.aggregate(),
          result.page(),
          result.size(),
          result.excludedRosters(),
          result.rows().stream()
              .map(
                  row ->
                      new RosterRankingRowResponse(
                          row.rank(),
                          row.serverName(),
                          row.characterName(),
                          row.className(),
                          row.itemLevel(),
                          row.combatPower(),
                          row.characterCount(),
                          row.usedCharacters(),
                          row.value(),
                          row.composition().stream().map(RosterMemberResponse::from).toList()))
              .toList());
    }
  }

  public record RosterRankingRowResponse(
      @Schema(description = "같은 값(소수 둘째 자리)은 같은 순위") long rank,
      String serverName,
      @Schema(description = "대표 캐릭터(아이템 레벨이 가장 높은 공개 캐릭터)") String characterName,
      @NullableResponse String className,
      @NullableResponse BigDecimal itemLevel,
      @Schema(description = "대표 캐릭터 전투력(관측하지 못했으면 null)") @NullableResponse BigDecimal combatPower,
      @Schema(description = "이 원정대에서 확인한 공개 캐릭터 수(실제 원정대 인원이 아니라 관측된 캐릭터 가운데 처리정지 목록를 뺀 수)")
          long characterCount,
      @Schema(description = "값 계산에 쓴 캐릭터 수(TOP6는 최대 6, 화면의 n/6). ROSTER_LEVEL이면 원정대 레벨을 확인한 캐릭터 수")
          long usedCharacters,
      @Schema(
              description =
                  "basis·aggregate로 계산한 값, 소수 둘째 자리. 단위는 basis를 따름(ITEM_LEVEL 아이템 레벨, COMBAT_POWER 전투력, ROSTER_LEVEL 원정대 레벨)",
              example = "1712.50")
          BigDecimal value,
      @Schema(description = "기준 상위 최대 6명(값이 있는 공개 캐릭터만, 순서대로). ROSTER_LEVEL이면 아이템 레벨 상위")
          List<RosterMemberResponse> composition) {}

  public record RosterMemberResponse(
      String characterName,
      @NullableResponse String className,
      @Schema(description = "그 캐릭터의 기준 값(ROSTER_LEVEL이면 아이템 레벨)") BigDecimal value) {
    static RosterMemberResponse from(RosterMember member) {
      return new RosterMemberResponse(member.characterName(), member.className(), member.value());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "ranking.rosters", name = "enabled", havingValue = "true")
  static class RosterRankingOpenApiConfiguration {
    @Bean
    OpenApiCustomizer rosterRankingResponseContract() {
      return document -> {
        ResponseContracts.apply(
            RosterRankingRowResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(RosterMemberResponse.class, document.getComponents().getSchemas());
      };
    }
  }
}
