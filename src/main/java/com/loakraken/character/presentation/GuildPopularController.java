package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterPopularPort.PopularGuildResult;
import com.loakraken.character.application.CharacterPopularService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.ResponseContracts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인기 길드(M21). 길드 이름이 드러나므로 인기 캐릭터 플래그(character.popular.enabled)와 길드 랭킹
 * 플래그(ranking.guilds.enabled)가 둘 다 켜져야 열린다. 하나만 켜진 배포에서는 경로가 없다(404).
 */
@ConditionalOnExpression(GuildPopularController.ENABLED)
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class GuildPopularController {
  static final String ENABLED =
      "${character.popular.enabled:true} and ${ranking.guilds.enabled:false}";
  static final String RULE_VERSION = "popular-guild-v1";
  private final CharacterPopularService popular;

  public GuildPopularController(CharacterPopularService popular) {
    this.popular = popular;
  }

  @GetMapping(value = "/api/v1/guilds/popular", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "인기 길드",
      description =
          "LOAKraken 캐릭터 검색으로 집계한 인기 길드입니다(popular-guild-v1). 순위 기준: 집계 시점에 그 길드로 관측된 캐릭터들의 고유 조회 합(인기 캐릭터와 같은 기준 — 같은 클라이언트·같은 캐릭터를 KST 하루 1회만 셈, 24h는 최근 24개 시간 버킷, 7d는 168개)이며, 합이 인기 캐릭터와 같은 최소 기준 이상인 길드만 올립니다. "
              + "조회 수 숫자는 공개하지 않습니다. 같은 합은 길드 이름순으로 1, 2, 3 차례입니다. 기간 전체 고유 조회가 30회 미만이면 sampleStatus=COLLECTING이고 목록이 비어 있습니다. "
              + "처리정지 목록에 있는 캐릭터의 조회는 합에 넣지 않습니다(길드는 남음). 10분 캐시이며 공식 API를 부르지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "GUILD_POPULAR_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: server·period·limit 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_POPULAR_DISABLED 또는 CHARACTER_POPULAR_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<GuildPopularResponse> popular(
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "24h(최근 24시간, 기본) 또는 7d(최근 7일)")
          @RequestParam(required = false)
          @Pattern(regexp = "24h|7d")
          String period,
      @Parameter(description = "개수(기본 10, 최대 50)")
          @RequestParam(required = false)
          @Min(1)
          @Max(CharacterPopularService.MAX_LIMIT)
          Integer limit) {
    return ApiResponse.of(
        CharacterSuccessCode.GUILD_POPULAR_SUCCESS,
        GuildPopularResponse.from(
            popular.popularGuilds(
                CharacterPopularService.Period.of(period == null ? "24h" : period),
                server,
                limit == null ? 10 : limit)));
  }

  @Schema(description = "인기 길드(popular-guild-v1). 순위 기준은 길드원 캐릭터들의 고유 조회 합이며, 숫자는 공개하지 않습니다.")
  public record GuildPopularResponse(
      @Schema(allowableValues = {"24h", "7d"}) String period,
      Instant aggregatedAt,
      @Schema(
              description = "COLLECTING이면 items가 비어 있습니다",
              allowableValues = {"COLLECTING", "READY"})
          String sampleStatus,
      @Schema(example = RULE_VERSION) String ruleVersion,
      List<GuildPopularItemResponse> items) {
    static GuildPopularResponse from(PopularGuildResult result) {
      return new GuildPopularResponse(
          result.period(),
          result.aggregatedAt(),
          result.sampleStatus(),
          RULE_VERSION,
          result.items().stream()
              .map(
                  item ->
                      new GuildPopularItemResponse(
                          item.rank(), item.serverName(), item.guildName()))
              .toList());
    }
  }

  public record GuildPopularItemResponse(
      @Schema(description = "1부터 차례(같은 합은 길드 이름순)") int rank,
      String serverName,
      @Schema(description = "공식 프로필 길드 이름") String guildName) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression(GuildPopularController.ENABLED)
  static class GuildPopularOpenApiConfiguration {
    @Bean
    OpenApiCustomizer guildPopularResponseContract() {
      return document -> {
        var schemas = document.getComponents().getSchemas();
        ResponseContracts.apply(GuildPopularResponse.class, schemas);
        ResponseContracts.apply(GuildPopularItemResponse.class, schemas);
      };
    }
  }
}
