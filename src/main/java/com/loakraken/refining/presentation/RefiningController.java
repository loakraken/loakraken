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
  /** 시세 예열 주기보다 짧게(다시 요청하면 그사이 채워졌을 수 있다). */
  static final java.time.Duration RETRY_AFTER = java.time.Duration.ofSeconds(60);

  private final RefiningPlanService service;
  private final com.loakraken.market.application.RefiningMaterialPriceService materials;
  private final org.springframework.core.env.Environment environment;

  RefiningController(
      RefiningPlanService service,
      com.loakraken.market.application.RefiningMaterialPriceService materials,
      org.springframework.core.env.Environment environment) {
    this.service = service;
    this.materials = materials;
    this.environment = environment;
  }

  /**
   * OPTIMAL 스위치(refining.optimal.enabled, 기본 꺼짐 — 화면이 v0.3.4에 붙을 때 켠다, 코드 리뷰 10-25). 요청마다 읽는다. 꺼져
   * 있으면 breath=OPTIMAL은 400이다(다른 방식으로 바꿔 계산하면 요청한 것과 다른 방법의 값을 주게 된다).
   */
  boolean optimalEnabled() {
    return environment.getProperty("refining.optimal.enabled", Boolean.class, false);
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
        description =
            "INVALID_REQUEST: 단계 범위·장인의 기운·실패 횟수 오류, useBreath와 breath가 어긋남, OPTIMAL 스위치가 꺼져 있는데 breath=OPTIMAL",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "REFINING_PRICE_PENDING: breath=OPTIMAL인데 필요한 재료 시세가 아직 없음(Retry-After 초). 다른 방식으로 대신 계산하지 않는다",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description =
            "REFINING_SOLVE_BUSY: breath=OPTIMAL인데 서버의 최적 풀이 자리가 모두 차 있음(기다리지 않음, Retry-After 초 뒤 다시)",
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
    if (request.effectiveBreath() == RefiningPlanRequest.Breath.OPTIMAL) {
      if (!optimalEnabled())
        // 400 INVALID_REQUEST(새 오류 코드 없음, 다른 400과 같은 처리)
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST);
      // 시세 캐시만 읽는다(공식 호출 없음). 시세가 없으면 숨결 없음·최대로 바꿔 계산하지 않는다.
      var prices =
          materials
              .cached()
              .orElseThrow(
                  () ->
                      new com.loakraken.common.api.BusinessException(
                          com.loakraken.refining.application.RefiningErrorCode
                              .REFINING_PRICE_PENDING,
                          RETRY_AFTER));
      var optimal =
          service.optimal(
              request.grade(), request.equipmentType(), request.toOptions(), prices.unitPrices());
      if (optimal == null)
        throw new com.loakraken.common.api.BusinessException(
            com.loakraken.refining.application.RefiningErrorCode.REFINING_PRICE_PENDING,
            RETRY_AFTER);
      return ApiResponse.of(
          RefiningSuccessCode.REFINING_PLAN_SUCCESS,
          RefiningPlanResponse.from(
              service.rules(),
              service.growthCosts(),
              request.grade(),
              request.equipmentType(),
              request,
              optimal.plan(),
              optimal.stages(),
              prices.observedAt()));
    }
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
