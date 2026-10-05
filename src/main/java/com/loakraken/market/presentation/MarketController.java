package com.loakraken.market.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.market.application.MarketQueryService;
import com.loakraken.market.application.MarketSource;
import com.loakraken.market.application.MaterialQuoteService;
import com.loakraken.market.application.MaterialUnitPriceService;
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
      description = "거래소 조회 성공"),
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
      description = "LOST_ARK_NOT_CONFIGURED 또는 MARKET_CACHE_UNAVAILABLE",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "504",
      description = "LOST_ARK_API_TIMEOUT",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
})
public class MarketController {
  private final MarketQueryService service;
  private final MaterialQuoteService quotes;
  private final MaterialUnitPriceService unitPrices;

  public MarketController(
      MarketQueryService service,
      MaterialQuoteService quotes,
      MaterialUnitPriceService unitPrices) {
    this.service = service;
    this.quotes = quotes;
    this.unitPrices = unitPrices;
  }

  @GetMapping("/items")
  @Operation(
      summary = "현재 판매 상품 가격 조회",
      description =
          "공식 현재 판매 상품을 조회합니다. 기본 캐시는 30초이며 observedAt은 원래 수집 시각입니다. 적용한 검색 조건을 함께 반환합니다.")
  public ApiResponse<MarketPriceResponse> items(
      @Valid @ModelAttribute @ParameterObject MarketSearchRequest request) {
    return search(MarketSource.ACTIVE_LISTING, request);
  }

  @GetMapping("/trades")
  @Operation(
      summary = "최근 거래 상품 가격 조회",
      description = "공식 최근 거래 상품을 조회합니다. 기본 캐시는 30초이며 개별 거래 시각과 집계 기간은 제공되지 않습니다.")
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

  @GetMapping("/options")
  @Operation(
      summary = "거래소 검색 옵션 조회",
      description =
          "공식 카테고리·하위 카테고리·등급·티어·직업 목록입니다. 기본 캐시는 10분이며 observedAt은 원래 수집 시각입니다. 가격 검색 시 자동으로 선행 호출하지 않습니다.")
  public ApiResponse<MarketOptionsResponse> options() {
    return ApiResponse.of(
        MarketSuccessCode.MARKET_OPTIONS_SUCCESS, MarketOptionsResponse.from(service.options()));
  }

  @PostMapping(value = "/material-quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "재료 구매 견적 계산",
      description =
          "필요·보유 수량과 선택한 공식 묶음 가격으로 계산합니다. 최대 10개 항목의 검색 페이지를 캐시·공유 호출 예산 안에서 순차 조회합니다. "
              + "미계산 항목이 있으면 complete=false와 totalCostGold=null입니다. 재련 기대 비용·매물 수량 보장·자동 구매는 제공하지 않습니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "415",
      description = "UNSUPPORTED_MEDIA_TYPE",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  public ApiResponse<MaterialQuoteResponse> materialQuote(
      @Valid @RequestBody MaterialQuoteRequest request) {
    return ApiResponse.of(
        MarketSuccessCode.MATERIAL_QUOTE_SUCCESS,
        MaterialQuoteResponse.from(quotes.quote(request.toCommand())));
  }

  @PostMapping(value = "/material-prices", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "재료 이름별 1개당 시세",
      description =
          "재료 이름(최대 20개)마다 강화 재료 분류의 현재 판매 목록 첫 페이지에서 이름이 정확히 같은 상품을 찾아 선택한 기준 가격 ÷ 묶음 수로 1개당 골드를 계산합니다. "
              + "운명의 파편은 운명의 파편 주머니(소 1,000·중 2,000·대 3,000개, 공식 Tooltip)를 파편 1개당 가격으로 바꿔 가장 싼 주머니를 고릅니다. "
              + "이름별 검색 페이지를 기존 30초 캐시·공유 호출 예산 안에서 조회합니다. 재련 계획의 재료 이름을 그대로 넣을 수 있습니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "415",
      description = "UNSUPPORTED_MEDIA_TYPE",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  public ApiResponse<MaterialPriceResponse> materialPrices(
      @Valid @RequestBody MaterialPriceRequest request) {
    return ApiResponse.of(
        MarketSuccessCode.MATERIAL_PRICE_SUCCESS,
        MaterialPriceResponse.from(
            request.priceBasis(),
            unitPrices.prices(request.priceBasis(), request.normalizedNames())));
  }
}
