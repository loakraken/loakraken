package com.loakraken.stats.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.stats.application.StatsQueryService;
import com.loakraken.stats.domain.GuildDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 길드 탭 집계(L36·K28·M10). 길드 랭킹과 같은 길드 데이터라 같은 플래그를 따른다. */
@ConditionalOnProperty(prefix = "ranking.guilds", name = "enabled", havingValue = "true")
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class GuildDetailController {
  private final StatsQueryService stats;

  public GuildDetailController(StatsQueryService stats) {
    this.stats = stats;
  }

  @GetMapping(
      value = "/api/v1/guilds/{serverName}/{guildName}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "길드 탭 집계",
      description =
          "한 길드에서 최근 activeDays일 안에 프로필을 관측한 공개 길드원(길드 랭킹 memberCount와 같은 정의)으로 만든 목록·평균·중앙값·분포·직업 분포입니다(guild-detail-v1). "
              + "표본 방식이라 실제 길드 전체가 아닙니다. 길드 이름은 대소문자를 무시해 찾습니다. 처리정지 캐릭터는 목록·집계 모두에 없습니다. "
              + "길드원이 5명 미만이면 sampleStatus=SMALL이지만 값은 그대로 줍니다. 관측 길드원이 없으면 404 STATS_GUILD_NOT_OBSERVED입니다. "
              + "400 INVALID_REQUEST, 404 STATS_GUILD_NOT_OBSERVED, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<GuildDetailResponse> guild(
      @Parameter(description = "서버 이름")
          @PathVariable
          @Size(min = 1, max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String serverName,
      @Parameter(description = "길드 이름(1~12자, 글자·숫자)")
          @PathVariable
          @Size(min = 1, max = 12)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String guildName,
      @Parameter(description = "최근 며칠 안에 프로필을 관측한 길드원(7·30·90, 기본 30)")
          @RequestParam(required = false)
          Integer activeDays) {
    return ApiResponse.of(
        StatsSuccessCode.GUILD_DETAIL_SUCCESS,
        GuildDetailResponse.from(
            stats.guildDetail(serverName, guildName, ActiveDays.of(activeDays))));
  }

  public record GuildDetailResponse(
      GuildDetailMeta meta,
      String serverName,
      @Schema(description = "저장된 길드 이름 철자") String guildName,
      GuildDetailSummary summary,
      @Schema(description = "인원 많은 순, 같으면 직업 이름순") List<GuildClassCount> classes,
      @Schema(description = "아이템 레벨 구간(폭 10), 낮은 구간부터") List<GuildBin> itemLevelBins,
      @Schema(description = "전투력 구간(폭 100), 전투력을 관측한 길드원만") List<GuildBin> combatPowerBins,
      @Schema(description = "아이템 레벨 높은 순, 같으면 이름순. 전원") List<GuildMemberResponse> members) {
    static GuildDetailResponse from(StatsQueryService.GuildDetailResult result) {
      var detail = result.detail();
      var summary = detail.summary();
      return new GuildDetailResponse(
          new GuildDetailMeta(
              detail.memberCount(),
              detail.sampleStatus(),
              GuildDetail.MIN_MEMBERS,
              result.activeDays(),
              result.aggregatedAt(),
              GuildDetail.RULE_VERSION),
          result.serverName(),
          result.guildName(),
          new GuildDetailSummary(
              summary.itemLevelAvg(),
              summary.itemLevelMedian(),
              summary.itemLevelMax(),
              summary.itemLevelMin(),
              summary.combatPowerAvg(),
              summary.combatPowerMedian(),
              summary.combatPowerCount()),
          detail.classes().stream()
              .map(row -> new GuildClassCount(row.className(), row.count(), row.share()))
              .toList(),
          bins(detail.itemLevelBins()),
          bins(detail.combatPowerBins()),
          detail.members().stream()
              .map(
                  member ->
                      new GuildMemberResponse(
                          member.characterName(),
                          member.className(),
                          member.itemLevel(),
                          member.combatPower(),
                          member.guildMemberGrade(),
                          member.observedAt()))
              .toList());
    }

    private static List<GuildBin> bins(List<GuildDetail.Bin> bins) {
      return bins.stream().map(bin -> new GuildBin(bin.from(), bin.count())).toList();
    }
  }

  public record GuildDetailMeta(
      @Schema(description = "관측한 공개 길드원 수(실제 길드 인원이 아님)", example = "12") long memberCount,
      @Schema(description = "SMALL(길드원 minMembers 미만) 또는 READY. 문자열이며 값이 늘 수 있음", example = "READY")
          String sampleStatus,
      @Schema(description = "길드 랭킹에 넣는 최소 길드원 수", example = "5") int minMembers,
      @Schema(example = "30") int activeDays,
      Instant aggregatedAt,
      @Schema(example = "guild-detail-v1") String ruleVersion) {}

  @Schema(description = "소수 둘째 자리. 전투력 값은 전투력을 관측한 길드원(combatPowerCount)만으로 냄")
  public record GuildDetailSummary(
      BigDecimal itemLevelAvg,
      BigDecimal itemLevelMedian,
      BigDecimal itemLevelMax,
      BigDecimal itemLevelMin,
      @Schema(description = "전투력을 관측한 길드원이 없으면 null") @NullableResponse BigDecimal combatPowerAvg,
      @Schema(description = "전투력을 관측한 길드원이 없으면 null") @NullableResponse
          BigDecimal combatPowerMedian,
      long combatPowerCount) {}

  public record GuildClassCount(
      String className,
      long count,
      @Schema(description = "길드원 대비 %, 소수 둘째 자리", example = "25.00") BigDecimal share) {}

  public record GuildBin(@Schema(description = "구간 아래 끝(포함)") BigDecimal from, long count) {}

  public record GuildMemberResponse(
      String characterName,
      String className,
      BigDecimal itemLevel,
      @Schema(description = "관측한 전투력(없으면 null)") @NullableResponse BigDecimal combatPower,
      @Schema(description = "공식 길드 직위 문자열 그대로(아직 확인 못 했으면 null). 문자열이며 값이 늘 수 있음", example = "길드원")
          @NullableResponse
          String guildMemberGrade,
      @Schema(description = "마지막 프로필 관측 시각(분 단위)") Instant observedAt) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "ranking.guilds", name = "enabled", havingValue = "true")
  static class GuildDetailOpenApiConfiguration {
    @Bean
    OpenApiCustomizer guildDetailResponseContract() {
      return document ->
          ResponseContracts.apply(GuildDetailResponse.class, document.getComponents().getSchemas());
    }
  }
}
