package com.loakraken.market.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.market.application.MarketMinuteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 거래소 분 단위 차트(K35). 예열이 이미 받은 가격만 쓰고 공식 API를 부르지 않는다. */
@RestController
@Validated
@Tag(name = "거래소")
public class MarketMinuteController {
  private final MarketMinuteService minutes;

  public MarketMinuteController(MarketMinuteService minutes) {
    this.minutes = minutes;
  }

  @GetMapping(
      value = "/api/v1/markets/items/{itemId}/minutes",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "상품 분 단위 시세",
      description =
          "시세 예열이 이미 받은 가격을 관측 시각의 UTC 분 칸으로 모은 최근 minutes분(1~120, 기본 60)입니다. 공식 API를 부르지 않습니다. "
              + "관측한 분만 점으로 주고 빈 분은 채우지 않습니다. 품목마다 다시 받는 주기가 달라(sampleEverySeconds, 예: 30·600·1800초) 분마다 점이 있는 것은 짧은 주기 품목뿐입니다. "
              + "예열하지 않는 품목은 빈 points입니다. 400 INVALID_REQUEST")
  public ApiResponse<MarketMinuteChartResponse> minutes(
      @Parameter(description = "공식 거래소 상품 id") @PathVariable @Positive @Max(999_999_999)
          long itemId,
      @Parameter(description = "최근 몇 분(1~120, 기본 60)") @RequestParam(required = false)
          Integer minutes) {
    int window = minutes == null ? MarketMinuteService.DEFAULT_MINUTES : minutes;
    // 명시 검증: 범위 밖은 400(조건 객체 예외가 500이 되지 않게).
    if (window < 1 || window > MarketMinuteService.MAX_MINUTES)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return ApiResponse.of(
        MarketSuccessCode.MARKET_ITEM_MINUTES_SUCCESS,
        MarketMinuteChartResponse.from(this.minutes.chart(itemId, window)));
  }

  public record MarketMinuteChartResponse(
      long itemId,
      @Schema(description = "관측한 적이 없으면 null") @NullableResponse String itemName,
      @Schema(description = "칸 크기(초). 늘 60", example = "60") int intervalSeconds,
      @Schema(description = "이 품목을 다시 받는 실제 주기(초, 마지막 점 기준). 점이 없으면 null", example = "30")
          @NullableResponse
          Integer sampleEverySeconds,
      @Schema(description = "첫 칸(포함), UTC 분") Instant from,
      @Schema(description = "마지막 칸(포함, 진행 중인 분), UTC 분") Instant to,
      @Schema(description = "관측한 분만, 오래된 분부터") List<MarketMinutePointResponse> points) {
    static MarketMinuteChartResponse from(MarketMinuteService.MinuteChart chart) {
      return new MarketMinuteChartResponse(
          chart.itemId(),
          chart.itemName(),
          chart.intervalSeconds(),
          chart.sampleEverySeconds(),
          chart.from(),
          chart.to(),
          chart.points().stream()
              .map(
                  point ->
                      new MarketMinutePointResponse(
                          point.minute(), point.currentMinPrice(), point.recentPrice()))
              .toList());
    }
  }

  public record MarketMinutePointResponse(
      @Schema(description = "관측 시각의 UTC 분") Instant minute,
      @Schema(description = "판매 최저가(매물 없으면 null)") @NullableResponse BigDecimal currentMinPrice,
      @Schema(description = "최근 거래가(없으면 null)") @NullableResponse BigDecimal recentPrice) {}

  @Configuration(proxyBeanMethods = false)
  static class MarketMinuteOpenApiConfiguration {
    @Bean
    OpenApiCustomizer marketMinuteResponseContract() {
      return document ->
          ResponseContracts.apply(
              MarketMinuteChartResponse.class, document.getComponents().getSchemas());
    }
  }
}
