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
            server, null, null, ActiveDays.of(activeDays), null, null, null, null, false);
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
                            row.title(),
                            row.equippedRosters(),
                            row.equippedPercent(),
                            row.observedRosters(),
                            row.observedPercent(),
                            row.newRosters30d(),
                            row.observedCharacters()))
                .toList()));
  }

  @GetMapping(value = "/api/v1/emblems/equipped", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "휘장 장착 분포",
      description =
          "activeDays 안에 프로필(휘장 정보 포함)을 관측한 공개 캐릭터 N명(meta.population) 가운데 휘장마다 장착한 캐릭터 수와 비율입니다. 캐릭터 단위입니다. "
              + "공식 프로필에는 휘장 이름이 없어 공식 아이콘 주소(iconUrl)가 휘장을 구분합니다(주소만 주고 이미지는 저장하지 않음). 장착 중인 휘장만 알 수 있어 가진 휘장 전체는 알 수 없습니다. "
              + "모집단이 30 미만이면 sampleStatus=COLLECTING이지만 비율은 그대로 줍니다(참고값). "
              + "400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<EmblemEquipResponse> emblems(
      @Parameter(description = "서버 이름. 없으면 전체")
          @RequestParam(required = false)
          @Size(max = 16)
          @jakarta.validation.constraints.Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server,
      @Parameter(description = "최근 며칠 안에 관측한 캐릭터(7·30·90, 기본 30)") @RequestParam(required = false)
          Integer activeDays) {
    var conditions =
        new StatsConditions(
            server, null, null, ActiveDays.of(activeDays), null, null, null, null, false);
    var result = stats.emblemEquip(conditions);
    return ApiResponse.of(
        StatsSuccessCode.TITLE_EQUIP_SUCCESS,
        new EmblemEquipResponse(
            StatsResponses.StatsMetaResponse.from(result.meta()),
            result.withoutEmblem(),
            result.emblems().stream()
                .map(
                    row ->
                        new EquippedEmblemResponse(row.iconUrl(), row.characters(), row.percent()))
                .toList()));
  }

  public record EmblemEquipResponse(
      StatsResponses.StatsMetaResponse meta,
      @Schema(description = "모집단 가운데 휘장을 장착하지 않은 캐릭터 수") long withoutEmblem,
      @Schema(description = "휘장별(장착 캐릭터 수 내림차순, 같으면 주소의 코드 포인트 순)")
          List<EquippedEmblemResponse> emblems) {}

  public record EquippedEmblemResponse(
      @Schema(description = "공식 휘장 아이콘 주소(공식 이미지 서버, 쿼리 없이 정규화). 휘장을 구분하는 값") String iconUrl,
      @Schema(description = "그 휘장을 장착한 캐릭터 수") long characters,
      @Schema(description = "characters / population × 100, 소수 둘째 자리. population이 0이면 null")
          @NullableResponse
          BigDecimal percent) {}

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
          BigDecimal equippedPercent,
      @Schema(
              description =
                  "같은 모집단 원정대 가운데 그 칭호 장착이 기간과 관계없이 한 번이라도 기록된 원정대 수. 가진 원정대 수의 하한('관측'), 보유율이 아님")
          long observedRosters,
      @Schema(description = "observedRosters / population × 100, 소수 둘째 자리. population이 0이면 null")
          @NullableResponse
          BigDecimal observedPercent,
      @Schema(description = "그 원정대에서 이 칭호를 LOAKraken이 처음 기록한 때가 최근 30일 안인 원정대 수(activeDays와 무관)")
          long newRosters30d,
      @Schema(
              description =
                  "observedRosters의 원정대들에서 이 칭호 장착이 기록된 서로 다른 공개 캐릭터 수. 희소도 보기·가장 드문 칭호 목록은 이 값이 30 이상인 칭호만 넣는다(운영 판정)")
          long observedCharacters) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "ranking.titles", name = "enabled", havingValue = "true")
  static class TitleEquipOpenApiConfiguration {
    @Bean
    OpenApiCustomizer titleEquipResponseContract() {
      return document -> {
        ResponseContracts.apply(TitleEquipResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(EquippedTitleResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(EmblemEquipResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(
            EquippedEmblemResponse.class, document.getComponents().getSchemas());
      };
    }
  }
}
