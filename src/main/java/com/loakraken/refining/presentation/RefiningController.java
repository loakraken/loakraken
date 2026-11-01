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
  private final com.loakraken.market.application.MaterialUnitPriceService unitPrices;
  private final org.springframework.core.env.Environment environment;

  RefiningController(
      RefiningPlanService service,
      com.loakraken.market.application.RefiningMaterialPriceService materials,
      com.loakraken.market.application.MaterialUnitPriceService unitPrices,
      org.springframework.core.env.Environment environment) {
    this.service = service;
    this.materials = materials;
    this.unitPrices = unitPrices;
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
            "INVALID_REQUEST: 단계 범위·장인의 기운·실패 횟수 오류, useBreath와 breath가 어긋남, OPTIMAL 스위치가 꺼져 있는데 breath=OPTIMAL. REFINING_SUCCESS_RATE_MISMATCH: currentSuccessRate가 첫 단계 기본 확률 × (1 + 0.1 × 실패 0~10) 어느 값과도 같지 않음(숨결·책을 더한 값 등). REFINING_ARTISAN_ENERGY_MISMATCH: 성공 확률로 정한 실패 횟수와 currentArtisanEnergy가 맞지 않음(그 횟수만큼 실패해 쌓일 수 있는 기운 밖)",
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
    var prices =
        request.effectiveBreath() == RefiningPlanRequest.Breath.OPTIMAL ? requirePrices() : null;
    return ApiResponse.of(
        RefiningSuccessCode.REFINING_PLAN_SUCCESS, solve(request, prices).response());
  }

  @PostMapping(value = "/plans/total", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "티어 4 재련 여러 부위 합계(가진 재료 빼기)",
      description =
          "부위(1~6개)마다 /plans와 같은 계획을 만들고 화면 합계 한 줄을 준다. 재료마다 모든 부위·단계 기대 수량을 더해 한 번 올린 뒤 가진 수량을 빼고, 남은 수량은 재료 시세 API와 같은 매물·값(priceBasis 기준)으로 통째로 산다고 보고 골드로 바꾼다. "
              + "재련 골드는 모든 단계 골드를 더해 한 번 올린다. 가진 재료는 계획(숨결·책 선택)을 바꾸지 않는다. "
              + "재료 값은 서버 캐시만 읽는다(공식 호출 없음, 캐시에 없는 재료는 unpricedMaterials). NONE·MAX는 시세가 없어도 200(골드 칸 null), OPTIMAL 계획 풀이는 /plans와 같이 즉시 구매 최저가로 하고 409·429·400.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "REFINING_PLAN_TOTAL_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description =
            "INVALID_REQUEST: 부위 0개·7개 이상, 단계 범위, 가진 수량 형식, OPTIMAL 스위치가 꺼져 있는데 breath=OPTIMAL. REFINING_SUCCESS_RATE_MISMATCH: 부위 currentSuccessRate가 그 부위 첫 단계 값과 맞지 않음. REFINING_ARTISAN_ENERGY_MISMATCH: 부위 성공 확률과 장인의 기운이 맞지 않음",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "REFINING_PRICE_PENDING: breath=OPTIMAL인데 재료 시세가 아직 없음(Retry-After 초)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description =
            "REFINING_SOLVE_BUSY: breath=OPTIMAL인데 최적 풀이 자리가 모두 차 있음(기다리지 않음, Retry-After 초 뒤 다시)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<RefiningPlanTotalResponse> total(
      @Valid @RequestBody RefiningPlanTotalRequest request) {
    var optimal = request.effectiveBreath() == RefiningPlanRequest.Breath.OPTIMAL;
    var prices = optimal ? requirePrices() : null;
    var parts = new java.util.ArrayList<RefiningPlanTotalResponse.Solved>();
    for (var part : request.parts()) parts.add(solve(request.toPlanRequest(part), prices));
    var basis = request.effectivePriceBasis();
    // 사야 할 골드는 화면과 같은 재료 시세 경로로(캐시만, 검색·공식 호출 없음).
    return ApiResponse.of(
        RefiningSuccessCode.REFINING_PLAN_TOTAL_SUCCESS,
        RefiningPlanTotalResponse.of(
            request.effectiveBreath(),
            basis,
            parts,
            request.owned(),
            names -> unitPrices.cachedPrices(basis, names)));
  }

  /** OPTIMAL에 쓸 시세. 스위치가 꺼져 있으면 400, 시세가 없으면 409(다른 방식으로 바꿔 계산하지 않는다). */
  private com.loakraken.market.application.RefiningMaterialPriceService.Prices requirePrices() {
    if (!optimalEnabled())
      // 400 INVALID_REQUEST(새 오류 코드 없음, 다른 400과 같은 처리)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST);
    // 시세 캐시만 읽는다(공식 호출 없음). 시세가 없으면 숨결 없음·최대로 바꿔 계산하지 않는다.
    return materials
        .cached()
        .orElseThrow(
            () ->
                new com.loakraken.common.api.BusinessException(
                    com.loakraken.refining.application.RefiningErrorCode.REFINING_PRICE_PENDING,
                    RETRY_AFTER));
  }

  /** 첫 단계 실패 횟수: 요청 값, 없으면 현재 성공 확률로 정한 값(맞는 값이 없으면 400), 둘 다 없으면 0. */
  private RefiningPlanResponse.StartFailures startFailures(RefiningPlanRequest request) {
    if (!request.failuresFromSuccessRate())
      return new RefiningPlanResponse.StartFailures(
          request.currentFailedAttempts() == null ? 0 : request.currentFailedAttempts(), "REQUEST");
    return new RefiningPlanResponse.StartFailures(
        service.failuresFromSuccessRate(
            request.grade(),
            request.equipmentType(),
            request.fromStage() + 1,
            RefiningPlanRequest.onHundredths(request.currentSuccessRate())
                ? request.currentSuccessRate().movePointRight(2).intValueExact()
                // 0.01% 단위가 아닌 값(3.305)은 어느 단계 값과도 같지 않다(성공 확률 불일치).
                : -1,
            request.currentArtisanEnergy() == null
                ? 0
                : request.currentArtisanEnergy().movePointRight(2).intValueExact()),
        "SUCCESS_RATE");
  }

  /** 한 부위 계획. OPTIMAL이면 prices로 풀고(값이 모자라면 409), 아니면 시세 없이 계산한다. */
  private RefiningPlanTotalResponse.Solved solve(
      RefiningPlanRequest request,
      com.loakraken.market.application.RefiningMaterialPriceService.Prices prices) {
    var start = startFailures(request);
    var options = request.toOptions(start.count());
    if (request.effectiveBreath() == RefiningPlanRequest.Breath.OPTIMAL) {
      var optimal =
          service.optimal(request.grade(), request.equipmentType(), options, prices.unitPrices());
      if (optimal == null)
        throw new com.loakraken.common.api.BusinessException(
            com.loakraken.refining.application.RefiningErrorCode.REFINING_PRICE_PENDING,
            RETRY_AFTER);
      return new RefiningPlanTotalResponse.Solved(
          RefiningPlanResponse.from(
              service.rules(),
              service.growthCosts(),
              service.vambrace(),
              request.grade(),
              request.equipmentType(),
              request,
              optimal.plan(),
              optimal.stages(),
              prices.observedAt(),
              start),
          optimal.plan(),
          optimal.stages());
    }
    var plan = service.plan(request.grade(), request.equipmentType(), options);
    return new RefiningPlanTotalResponse.Solved(
        RefiningPlanResponse.from(
            service.rules(),
            service.growthCosts(),
            service.vambrace(),
            request.grade(),
            request.equipmentType(),
            request,
            plan,
            start),
        plan,
        null);
  }
}
