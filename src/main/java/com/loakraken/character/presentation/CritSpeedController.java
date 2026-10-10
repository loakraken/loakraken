package com.loakraken.character.presentation;

import com.loakraken.character.application.CritSpeedService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.ResponseContracts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 치명타·공이속 계산. 웹 공개 판단 전까지 crit-speed.enabled(CRIT_SPEED_ENABLED, 기본 false)로 연다(PM-백엔드 10-10 09:00,
 * 배포·운영 09:04: 운영 env에 값을 넣지 않아도 꺼져야 안전). 개발·검증 서버에서만 켠다.
 */
@ConditionalOnProperty(prefix = "crit-speed", name = "enabled", havingValue = "true")
@RestController
@RequestMapping(value = "/api/v1/characters", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CritSpeedController {
  private final CritSpeedService critSpeed;

  public CritSpeedController(CritSpeedService critSpeed) {
    this.critSpeed = critSpeed;
  }

  @PostMapping(value = "/crit-speed", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "치명타·공이속 계산(치적·뭉가·음돌)",
      description =
          "캐릭터 검색 캐시를 공유해 공식 원문(치명·신속 Tooltip, 각인, 아크 패시브 노드, 장신구 연마, 팔찌, 카드)의 치명타 적중률과 공격·이동 속도를 합산하고, 요청 가정(백어택·펫·시너지·버프 등)을 분리해 더합니다. "
              + "치적 100%·뭉툭한 가시 최적 치적까지 필요한 치명과 음속 돌파 최적 속도까지 필요한 신속을 계산합니다. 원정대는 조회하지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "계산 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 캐릭터명·가정 범위 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "UNSUPPORTED_MEDIA_TYPE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "LOST_ARK_NOT_CONFIGURED 또는 CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CritSpeedResponse> critSpeed(@Valid @RequestBody CritSpeedRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_CRIT_SPEED_SUCCESS,
        CritSpeedResponse.from(
            critSpeed.calculate(request.normalizedName(), request.toAssumptions())));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "crit-speed", name = "enabled", havingValue = "true")
  static class CritSpeedOpenApiConfiguration {
    @Bean
    OpenApiCustomizer critSpeedResponseContract() {
      return document ->
          ResponseContracts.apply(CritSpeedResponse.class, document.getComponents().getSchemas());
    }
  }
}
