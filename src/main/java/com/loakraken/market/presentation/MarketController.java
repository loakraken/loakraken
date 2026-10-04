package com.loakraken.market.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.market.application.MarketQueryService;
import com.loakraken.market.application.MarketSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/markets", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "거래소", description = "공식 거래소 상품 가격 요약. 개별 매물·거래 체결 이력·추천 점수를 제공하지 않습니다.")
@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "가격 조회 성공"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "INVALID_REQUEST: 검색 조건 오류",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "406",
      description = "NOT_ACCEPTABLE",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "429",
      description = "LOST_ARK_RATE_LIMITED: 캐릭터와 공유하는 호출 한도 초과",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      description = "INTERNAL_SERVER_ERROR",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "502",
      description = "LOST_ARK_API_ERROR: 외부 검색 조건 거절 또는 응답 오류",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "503",
      description = "LOST_ARK_NOT_CONFIGURED",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "504",
      description = "LOST_ARK_API_TIMEOUT",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
})
public class MarketController {
  private final MarketQueryService service;

  public MarketController(MarketQueryService service) {
    this.service = service;
  }

  @GetMapping("/items")
  @Operation(
      summary = "현재 판매 상품 가격 조회",
      description = "공식 POST /markets/items를 한 번 조회합니다. 검색 조건의 기본값을 적용해 응답에 함께 반환합니다.")
  public ApiResponse<MarketPriceResponse> items(
      @Valid @ModelAttribute @ParameterObject MarketSearchRequest request) {
    return search(MarketSource.ACTIVE_LISTING, request);
  }

  @GetMapping("/trades")
  @Operation(
      summary = "최근 거래 상품 가격 조회",
      description = "공식 POST /markets/trades를 한 번 조회합니다. 개별 거래 시각과 집계 기간은 제공되지 않습니다.")
  public ApiResponse<MarketPriceResponse> trades(
      @Valid @ModelAttribute @ParameterObject MarketSearchRequest request) {
    return search(MarketSource.RECENT_TRADE, request);
  }

  private ApiResponse<MarketPriceResponse> search(
      MarketSource source, MarketSearchRequest request) {
    return ApiResponse.of(
        MarketSuccessCode.MARKET_SEARCH_SUCCESS,
        MarketPriceResponse.from(service.search(source, request.toSearch())));
  }
}
