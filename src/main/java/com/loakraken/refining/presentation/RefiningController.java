package com.loakraken.refining.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.refining.application.RefiningPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/refining", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "재련", description = "티어 4 장비 재련 기대 비용")
class RefiningController {
  private final RefiningPlanService service;

  RefiningController(RefiningPlanService service) {
    this.service = service;
  }

  @PostMapping(value = "/plans", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "티어 4 재련 기대 비용",
      description =
          "운명의 업화·운명의 전율 무기/방어구의 현재 단계부터 목표 단계(11~25)까지 기대·중앙값·90%·최대 시도 수와 재료·골드를 계산합니다. "
              + "외부 API·DB를 사용하지 않습니다. 단계별 수치는 MIT 라이선스 공개 데이터(고지는 저장소 NOTICE)를 전수 교차 검증한 값이며 공식 표가 아닙니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "REFINING_PLAN_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 단계 범위·장인의 기운·실패 횟수 오류",
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
  public ApiResponse<RefiningPlanResponse> plan(@Valid @RequestBody RefiningPlanRequest request) {
    var plan = service.plan(request.grade(), request.equipmentType(), request.toOptions());
    return ApiResponse.of(
        RefiningSuccessCode.REFINING_PLAN_SUCCESS,
        RefiningPlanResponse.from(
            service.rules(),
            service.growthCosts(),
            request.grade(),
            request.equipmentType(),
            request,
            plan));
  }
}
