package com.loakraken.market.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.market.application.MarketBoardService;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
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
  private final MarketBoardService board;

  public MarketController(
      MarketQueryService service,
      MaterialQuoteService quotes,
      MaterialUnitPriceService unitPrices,
      MarketBoardService board) {
    this.service = service;
    this.quotes = quotes;
    this.unitPrices = unitPrices;
    this.board = board;
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

  @GetMapping("/tabs")
  @Operation(summary = "시세 보드 탭", description = "보드 탭과 공식 카테고리 코드, 기본 티어, 정렬 값입니다. 외부 호출이 없습니다.")
  public ApiResponse<MarketTabsResponse> tabs() {
    return ApiResponse.of(MarketSuccessCode.MARKET_TABS_SUCCESS, MarketTabsResponse.create());
  }

  @GetMapping("/board")
  @Operation(
      summary = "시세 보드",
      description =
          "탭별 현재 판매 품목과 전일 대비 등락률입니다. default·high·low는 공식 정렬 한 페이지(검색 캐시 30초)를 씁니다. "
              + "rise·fall은 공식 정렬에 없어 탭 전체(최대 10페이지)를 순차 스캔하고 3분 캐시합니다. 동시 요청은 합칩니다. "
              + "changeWeek·spark7은 상품 이력 캐시가 있을 때만 채웁니다(추가 외부 호출 없음).")
  public ApiResponse<MarketBoardResponse> board(
      @Valid @ModelAttribute @ParameterObject MarketBoardRequest request) {
    return ApiResponse.of(
        MarketSuccessCode.MARKET_BOARD_SUCCESS,
        MarketBoardResponse.from(
            board.board(request.tab(), request.tier(), request.toSort(), request.pageOrDefault())));
  }

  @GetMapping("/items/{itemId}/history")
  @Operation(
      summary = "상품 14일 시세 이력",
      description =
          "공식 거래소 상품 상세의 최근 14일 일별 평균가·거래량입니다. 오늘(한국 날짜) 값은 진행 중인 부분 집계라 partial=true이며 요약에서 제외합니다. "
              + "기본 캐시 10분, 같은 상품 동시 요청 병합, 캐릭터·거래소 공유 호출 예산을 사용합니다. 공식 API가 모르는 상품은 404 MARKET_ITEM_NOT_FOUND입니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "MARKET_ITEM_NOT_FOUND",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  public ApiResponse<MarketItemHistoryResponse> itemHistory(
      @PathVariable @Positive @Max(999_999_999) long itemId) {
    return ApiResponse.of(
        MarketSuccessCode.MARKET_ITEM_HISTORY_SUCCESS,
        MarketItemHistoryResponse.from(service.itemHistory(itemId)));
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
          "재료 이름(최대 20개)마다 이름이 정확히 같은 거래소 상품을 찾아 선택한 기준 가격 ÷ 묶음 수로 1개당 골드를 계산합니다. "
              + "먼저 서버 예열 시세(T4 재련 재료·추가 재료, 생활 분류 전체)에서 찾고, 없으면 강화 재료 분류 → 생활 분류 현재 판매 목록(분류마다 최대 3페이지)을 검색합니다. "
              + "운명의 파편은 운명의 파편 주머니(소 1,000·중 2,000·대 3,000개, 공식 Tooltip)를 파편 1개당 가격으로 바꿔 가장 싼 주머니를 고릅니다. "
              + "예열 시세에 없는 이름이 있는 요청은 클라이언트당 분당 재료 시세 한도에 한 번 세고(넘으면 429 CLIENT_RATE_LIMITED), 검색은 요청당 상한까지만 하며 넘거나 공유 호출 예산이 모자라면 그 이름은 PRICE_UNAVAILABLE입니다. "
              + "재련 계획의 재료 이름을 그대로 넣을 수 있습니다.")
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
            unitPrices.requestPrices(request.priceBasis(), request.normalizedNames())));
  }
}
