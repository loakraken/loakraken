package com.loakraken.gem.presentation;

import com.loakraken.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

// 첫 운영 릴리스에서는 웹이 쓰지 않고 비용이 로아또와 아직 맞지 않아 기본 꺼짐(gem.enabled, PM-백엔드 10-10 09:00).
@ConditionalOnProperty(prefix = "gem", name = "enabled", havingValue = "true")
@RestController
@RequestMapping(value = "/api/v1/gems", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "보석", description = "공식 합성 규칙에 따른 티어 4 보석 수량 계획")
class GemController {
  @PostMapping(value = "/synthesis-plans", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "티어 4 보석 합성 계획",
      description =
          "목표 레벨·개수와 사용 가능한 보유 보석으로 추가 필요 기준 레벨 수량과 합성 순서를 계산합니다. "
              + "같은 티어·같은 레벨 3개를 1개로 합성하며 결과 종류·스킬을 지정하지 않습니다. "
              + "READY는 입력 수량 기준이며 캐릭터 레벨·잠금·장착·합성 가능 여부를 검증한 상태가 아닙니다. 골드·DPS·승급·조율 계산은 포함하지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "GEM_SYNTHESIS_PLAN_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "406",
        description = "NOT_ACCEPTABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "UNSUPPORTED_MEDIA_TYPE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<GemSynthesisResponse> plan(@Valid @RequestBody GemSynthesisRequest request) {
    return ApiResponse.of(
        GemSuccessCode.GEM_SYNTHESIS_PLAN_SUCCESS, GemSynthesisResponse.from(request.calculate()));
  }
}
