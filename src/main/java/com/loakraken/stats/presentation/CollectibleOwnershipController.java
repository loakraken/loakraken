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
 * 수집품 항목별 보유율(#305, 볼트 「수집품 순위·보유율 설계 메모」 2절). 수집품 랭킹과 같은 플래그(ranking.collectibles.enabled)로 켠다. 공식
 * API를 부르지 않고 관측 DB에서 집계해 조건별로 10분 캐시한다.
 */
@ConditionalOnProperty(prefix = "ranking.collectibles", name = "enabled", havingValue = "true")
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class CollectibleOwnershipController {
  private final StatsQueryService stats;

  public CollectibleOwnershipController(StatsQueryService stats) {
    this.stats = stats;
  }

  @GetMapping(value = "/api/v1/collectibles/ownership", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "수집품 항목별 보유율",
      description =
          "공식 수집품 항목(CollectiblePoints)마다, activeDays 안에 관측한 원정대(공개 캐릭터가 있는 원정대) 가운데 그 항목을 가진 원정대 수·비율입니다. "
              + "분모는 meta.population(원정대 N개)이고, ownedRosters = Point가 0보다 큰 원정대, completedRosters = Point가 만점 이상인 원정대입니다(0/1 항목은 둘이 같음). "
              + "항목 키는 (type, name)입니다(공식 문자열 그대로, 같은 종류 안 같은 이름은 한 항목으로 합칩니다). activeDays 안에 관측되지 않은 항목(게임에서 사라진 항목)은 빠집니다. type을 주면 그 종류만, 없으면 전체 종류(공식 순서)입니다. "
              + "모집단이 30 미만이면 sampleStatus=COLLECTING이지만 비율은 그대로 줍니다(참고값). 400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<CollectibleOwnershipResponse> ownership(
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "최근 며칠 안에 관측한 원정대(7·30·90, 기본 30)") @RequestParam(required = false)
          Integer activeDays,
      @Parameter(description = "공식 수집품 종류(예: 모코코 씨앗). 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 32)
          @Pattern(regexp = "[\\p{L}\\p{N} ]+")
          String type) {
    var conditions =
        new StatsConditions(
            server, null, null, ActiveDays.of(activeDays), null, null, null, null, false);
    var result = stats.collectibleOwnership(conditions, type == null ? null : type.strip());
    return ApiResponse.of(
        StatsSuccessCode.COLLECTIBLE_OWNERSHIP_SUCCESS,
        new CollectibleOwnershipResponse(
            StatsResponses.StatsMetaResponse.from(result.meta(), "ROSTER"),
            result.type(),
            result.items().stream()
                .map(
                    item ->
                        new OwnedItemResponse(
                            item.type(),
                            item.name(),
                            item.sortOrder(),
                            item.maxPoint(),
                            item.ownedRosters(),
                            item.completedRosters(),
                            item.ownedPercent(),
                            item.completedPercent()))
                .toList()));
  }

  public record CollectibleOwnershipResponse(
      StatsResponses.StatsMetaResponse meta,
      @Schema(description = "요청한 종류(없으면 null = 전체)") @NullableResponse String type,
      @Schema(description = "항목(종류는 공식 순서, 종류 안은 공식 순서)") List<OwnedItemResponse> items) {}

  public record OwnedItemResponse(
      @Schema(description = "공식 수집품 종류", example = "섬의 마음") String type,
      @Schema(description = "공식 항목 이름. (type, name)이 항목 키", example = "고블린 섬의 마음") String name,
      @Schema(description = "종류 안 공식 순서(1부터, 최근 관측)") int sortOrder,
      @Schema(description = "항목 만점(최근 관측). 모코코 씨앗은 지역별 개수") int maxPoint,
      @Schema(description = "Point가 0보다 큰 원정대 수(n)") long ownedRosters,
      @Schema(description = "Point가 만점 이상인 원정대 수") long completedRosters,
      @Schema(description = "ownedRosters / population × 100, 소수 둘째 자리. population이 0이면 null")
          @NullableResponse
          BigDecimal ownedPercent,
      @Schema(description = "completedRosters / population × 100, 소수 둘째 자리. population이 0이면 null")
          @NullableResponse
          BigDecimal completedPercent) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "ranking.collectibles", name = "enabled", havingValue = "true")
  static class CollectibleOwnershipOpenApiConfiguration {
    @Bean
    OpenApiCustomizer collectibleOwnershipResponseContract() {
      return document -> {
        ResponseContracts.apply(
            CollectibleOwnershipResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(OwnedItemResponse.class, document.getComponents().getSchemas());
      };
    }
  }
}
