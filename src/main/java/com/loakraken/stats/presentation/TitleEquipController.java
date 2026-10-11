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
 * 칭호 장착 확인 비율(#311, PM 총괄 10-11 15:02 판정). 공식 프로필에는 장착 칭호 하나만 있어 보유 여부는 알 수 없으므로, 관측 원정대 가운데 그 칭호
 * 장착이 확인된 원정대의 수·비율만 준다. 웹 화면이 준비될 때까지 기본 꺼짐(ranking.titles.enabled). 공식 API를 부르지 않는다.
 */
@ConditionalOnProperty(prefix = "ranking.titles", name = "enabled", havingValue = "true")
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class TitleEquipController {
  private final StatsQueryService stats;

  public TitleEquipController(StatsQueryService stats) {
    this.stats = stats;
  }

  @GetMapping(value = "/api/v1/titles/equipped", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "칭호 장착 확인 비율",
      description =
          "activeDays 안에 프로필을 관측한 원정대(공개 캐릭터가 있는 원정대) N개(meta.population) 가운데, 그 칭호를 장착한 공개 캐릭터가 같은 기간에 확인된 원정대 수(equippedRosters)와 비율입니다. "
              + "공식 프로필에는 장착 중인 칭호만 있어 가진 칭호 전체는 알 수 없습니다(장착하지 않은 칭호는 세지 않음). "
              + "title을 주면 그 칭호만, 없으면 확인된 칭호 전체(원정대 수 내림차순)입니다. 모집단이 30 미만이면 sampleStatus=COLLECTING이지만 비율은 그대로 줍니다(참고값). "
              + "400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<TitleEquipResponse> equipped(
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @jakarta.validation.constraints.Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "최근 며칠 안에 관측한 원정대(7·30·90, 기본 30)") @RequestParam(required = false)
          Integer activeDays,
      @Parameter(description = "공식 칭호 문자열. 없으면 전체") @RequestParam(required = false) @Size(max = 128)
          String title) {
    var conditions =
        new StatsConditions(
            server,
            null,
            null,
            activeDays == null ? StatsConditions.defaults().activeDays() : activeDays,
            null,
            null,
            null,
            null,
            false);
    var result =
        stats.titleEquip(conditions, title == null || title.isBlank() ? null : title.strip());
    return ApiResponse.of(
        StatsSuccessCode.TITLE_EQUIP_SUCCESS,
        new TitleEquipResponse(
            StatsResponses.StatsMetaResponse.from(result.meta(), "ROSTER"),
            result.title(),
            result.titles().stream()
                .map(
                    row ->
                        new EquippedTitleResponse(
                            row.title(), row.equippedRosters(), row.equippedPercent()))
                .toList()));
  }

  public record TitleEquipResponse(
      StatsResponses.StatsMetaResponse meta,
      @Schema(description = "요청한 칭호(없으면 null = 전체)") @NullableResponse String title,
      @Schema(description = "장착이 확인된 칭호(원정대 수 내림차순, 같으면 칭호 문자열의 코드 포인트 순)")
          List<EquippedTitleResponse> titles) {}

  public record EquippedTitleResponse(
      @Schema(description = "공식 칭호 문자열", example = "빛을 꺼트리는 자") String title,
      @Schema(description = "그 칭호 장착이 확인된 원정대 수(n). 같은 원정대의 여러 캐릭터는 한 번만 셈") long equippedRosters,
      @Schema(description = "equippedRosters / population × 100, 소수 둘째 자리. population이 0이면 null")
          @NullableResponse
          BigDecimal equippedPercent) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "ranking.titles", name = "enabled", havingValue = "true")
  static class TitleEquipOpenApiConfiguration {
    @Bean
    OpenApiCustomizer titleEquipResponseContract() {
      return document -> {
        ResponseContracts.apply(TitleEquipResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(EquippedTitleResponse.class, document.getComponents().getSchemas());
      };
    }
  }
}
