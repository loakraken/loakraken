package com.loakraken.market.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.market.application.AccessorySearchService;
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
import org.springframework.web.bind.annotation.RestController;

/** T4 악세 경매 검색기(N13). 기능 플래그 {@code loakraken.features.auction-search}가 꺼져 있으면 경로가 없다(404). */
@RestController
@ConditionalOnProperty(prefix = "loakraken.features", name = "auction-search", havingValue = "true")
@Tag(name = "경매장 시세")
public class AccessorySearchController {
  private final AccessorySearchService searches;

  public AccessorySearchController(AccessorySearchService searches) {
    this.searches = searches;
  }

  @PostMapping(
      value = "/api/v1/markets/auctions/accessories/search",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "T4 장신구 조건 검색",
      description =
          "연마 효과(최대 3줄, 하·중·상 또는 단계 무관)·깨달음 최솟값·최소 품질은 공식 경매장 서버 필터로, 힘민지 범위 %는 받은 페이지 안에서 거릅니다(최대 3페이지, complete·scannedPages·nextPage). "
              + "같은 조건은 120초 캐시(빈 결과 포함)하고, 캐시 미스 검색은 클라이언트당 분당 6회입니다. 검색이 몰리면 503 MARKET_SEARCH_BUSY와 Retry-After를 주며, 캐시에 있는 결과는 계속 줍니다. 읽기 전용입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "검색 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST 또는 INVALID_AUCTION_FILTER(부위에 없는 연마 효과, 같은 효과 두 번, 범위 밖 값)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "CLIENT_RATE_LIMITED(캐시 미스 검색·공식 호출 한도, Retry-After)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "MARKET_SEARCH_BUSY(Retry-After) 또는 LOST_ARK_NOT_CONFIGURED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<AccessorySearchResponses.AccessorySearchResponse> search(
      @Valid @RequestBody AccessorySearchRequest request) {
    return ApiResponse.of(
        MarketSuccessCode.AUCTION_SEARCH_SUCCESS,
        AccessorySearchResponses.AccessorySearchResponse.from(
            searches.search(request.toCommand())));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "loakraken.features",
      name = "auction-search",
      havingValue = "true")
  static class AccessorySearchOpenApiConfiguration {
    @Bean
    OpenApiCustomizer accessorySearchResponseContract() {
      return document ->
          ResponseContracts.apply(
              AccessorySearchResponses.AccessorySearchResponse.class,
              document.getComponents().getSchemas());
    }
  }
}
