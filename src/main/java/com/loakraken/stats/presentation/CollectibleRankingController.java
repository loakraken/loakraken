package com.loakraken.stats.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
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
import java.time.Instant;
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
 * 수집품(내실) 랭킹(#295). 웹 화면이 준비될 때까지 기본 꺼짐(ranking.collectibles.enabled). 공식 API를 부르지 않고 관측 DB에서 집계해
 * 조건별로 10분 캐시한다.
 */
@ConditionalOnProperty(prefix = "ranking.collectibles", name = "enabled", havingValue = "true")
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class CollectibleRankingController {
  private final StatsQueryService stats;

  public CollectibleRankingController(StatsQueryService stats) {
    this.stats = stats;
  }

  @GetMapping(value = "/api/v1/rankings/collectibles", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "수집품(내실) 랭킹",
      description =
          "공식 수집품(collectibles)의 Point·MaxPoint로 만든 진행도(Σ Point / Σ MaxPoint × 100) 순위입니다. 수집품은 원정대가 함께 모으므로 기본은 원정대 단위(대표 = 아이템 레벨이 가장 높은 공개 캐릭터)이고, unit=CHARACTER면 같은 원정대 캐릭터마다 같은 값으로 한 행씩 나옵니다. "
              + "동률은 진행도 → Σ Point 순이고, 같으면 같은 순위입니다. 하나도 모으지 않은 원정대와 activeDays 안에 관측하지 않은 원정대는 빠집니다. "
              + "처리정지 목록 캐릭터는 대표·행·캐릭터 수에서 빠지고, 공개 캐릭터가 없는 원정대는 빠집니다. 모집단이 30 미만이면 sampleStatus=COLLECTING이지만 값·순위는 그대로 줍니다(참고값). "
              + "400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<CollectibleRankingResponse> collectibles(
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "최근 며칠 안에 관측한 원정대(7·30·90, 기본 30)") @RequestParam(required = false)
          Integer activeDays,
      @Parameter(description = "ROSTER(기본, 원정대) 또는 CHARACTER(캐릭터)")
          @RequestParam(required = false)
          @Pattern(regexp = "ROSTER|CHARACTER")
          String unit,
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
    boolean characterUnit = "CHARACTER".equals(unit);
    return ApiResponse.of(
        StatsSuccessCode.COLLECTIBLE_RANKING_SUCCESS,
        CollectibleRankingResponse.from(
            stats.collectibleRanking(
                conditions, characterUnit, page == null ? 1 : page, size == null ? 50 : size)));
  }

  @GetMapping(
      value = "/api/v1/rankings/collectibles/standing",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "캐릭터 기준 수집품 순위",
      description =
          "그 캐릭터 원정대의 수집품 랭킹 순위·전체·상위 %·진행도입니다. 모집단·정렬·동률은 수집품 랭킹과 같습니다. "
              + "순위에 없으면 rank 등은 null이고 reason에 이유 코드(문자열, 값이 늘 수 있음)를 줍니다: NOT_OBSERVED(캐릭터·원정대·수집품 관측 없음), "
              + "INACTIVE(activeDays 안 관측 없음), NO_COLLECTIBLES(모은 수집품 없음). 모집단이 30 미만이면 topPercent는 null이고 rank는 그대로 줍니다(참고값). "
              + "400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<CollectibleStandingResponse> standing(
      @Parameter(description = "캐릭터 이름", required = true)
          @RequestParam
          @Size(min = 1, max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String characterName,
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "최근 며칠 안에 관측한 원정대(7·30·90, 기본 30)") @RequestParam(required = false)
          Integer activeDays,
      @Parameter(description = "ROSTER(기본, 원정대) 또는 CHARACTER(캐릭터)")
          @RequestParam(required = false)
          @Pattern(regexp = "ROSTER|CHARACTER")
          String unit) {
    var conditions =
        new StatsConditions(
            server, null, null, ActiveDays.of(activeDays), null, null, null, null, false);
    boolean characterUnit = "CHARACTER".equals(unit);
    var result = stats.collectibleStanding(conditions, characterName.strip(), characterUnit);
    return ApiResponse.of(
        StatsSuccessCode.COLLECTIBLE_STANDING_SUCCESS,
        new CollectibleStandingResponse(
            StatsResponses.StatsMetaResponse.from(result.meta(), result.unit()),
            result.unit(),
            result.characterName(),
            result.serverName(),
            result.rank(),
            result.total(),
            result.topPercent(),
            result.progressPercent(),
            result.point(),
            result.maxPoint(),
            result.reason()));
  }

  public record CollectibleStandingResponse(
      StatsResponses.StatsMetaResponse meta,
      @Schema(description = "ROSTER 또는 CHARACTER(문자열, 값이 늘 수 있음)", example = "ROSTER") String unit,
      String characterName,
      @Schema(description = "서버 이름(관측 없음이면 null)") @NullableResponse String serverName,
      @Schema(description = "같은 값은 같은 순위. 순위에 없으면 null") @NullableResponse Long rank,
      @Schema(description = "같은 조건의 순위 모집단 수") long total,
      @Schema(description = "상위 몇 %(소수 둘째 자리 올림, 다른 통계와 같음). 모집단 30 미만이거나 순위가 없으면 null")
          @NullableResponse
          BigDecimal topPercent,
      @Schema(description = "Σ Point / Σ MaxPoint × 100, 소수 둘째 자리. 순위가 없으면 null") @NullableResponse
          BigDecimal progressPercent,
      @NullableResponse Long point,
      @NullableResponse Long maxPoint,
      @Schema(
              description =
                  "순위에 없는 이유(문자열, 값이 늘 수 있음): NOT_OBSERVED, INACTIVE, NO_COLLECTIBLES(EXCLUDED는 더 이상 쓰지 않음). 순위가 있으면 null",
              example = "INACTIVE")
          @NullableResponse
          String reason) {}

  public record CollectibleRankingResponse(
      StatsResponses.StatsMetaResponse meta,
      @Schema(description = "ROSTER 또는 CHARACTER(문자열, 값이 늘 수 있음)", example = "ROSTER") String unit,
      int page,
      int size,
      List<CollectibleRankingRowResponse> rows) {
    static CollectibleRankingResponse from(StatsQueryService.CollectibleRankingResult result) {
      return new CollectibleRankingResponse(
          StatsResponses.StatsMetaResponse.from(result.meta(), result.unit()),
          result.unit(),
          result.page(),
          result.size(),
          result.rows().stream()
              .map(
                  row ->
                      new CollectibleRankingRowResponse(
                          row.rank(),
                          row.serverName(),
                          row.characterName(),
                          row.className(),
                          row.itemLevel(),
                          row.characterCount(),
                          row.progressPercent(),
                          row.point(),
                          row.maxPoint(),
                          row.observedAt(),
                          row.types().stream()
                              .map(
                                  type ->
                                      new CollectibleTypeResponse(
                                          type.type(), type.point(), type.maxPoint()))
                              .toList(),
                          row.imageUrl()))
              .toList());
    }
  }

  public record CollectibleRankingRowResponse(
      @Schema(description = "같은 값은 같은 순위") long rank,
      String serverName,
      @Schema(description = "ROSTER면 대표 캐릭터(아이템 레벨이 가장 높은 공개 캐릭터), CHARACTER면 그 캐릭터")
          String characterName,
      @NullableResponse String className,
      @NullableResponse BigDecimal itemLevel,
      @Schema(description = "이 원정대에서 확인한 공개 캐릭터 수(실제 원정대 인원이 아니라 관측된 캐릭터 가운데 처리정지 목록을 뺀 수)")
          long characterCount,
      @Schema(description = "Σ Point / Σ MaxPoint × 100, 소수 둘째 자리", example = "99.84")
          BigDecimal progressPercent,
      @Schema(description = "원정대 수집품 Point 합") long point,
      @Schema(description = "원정대 수집품 MaxPoint 합") long maxPoint,
      @Schema(description = "이 원정대 수집품을 마지막으로 관측한 시각") Instant observedAt,
      @Schema(description = "공식 수집품 종류(공식 순서, 종류가 늘 수 있는 배열)") List<CollectibleTypeResponse> types,
      @NullableResponse @Schema(description = "characterName 캐릭터의 " + StatsResponses.IMAGE_URL)
          String imageUrl) {}

  public record CollectibleTypeResponse(
      @Schema(description = "공식 Type 문자열", example = "모코코 씨앗") String type,
      int point,
      int maxPoint) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "ranking.collectibles", name = "enabled", havingValue = "true")
  static class CollectibleRankingOpenApiConfiguration {
    @Bean
    OpenApiCustomizer collectibleRankingResponseContract() {
      return document -> {
        ResponseContracts.apply(
            CollectibleRankingRowResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(
            CollectibleStandingResponse.class, document.getComponents().getSchemas());
      };
    }
  }
}
