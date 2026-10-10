package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.MarketItemHistoryResult;
import com.loakraken.market.domain.MarketItemHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Schema(description = "공식 거래소 상품의 최근 14일 일별 평균가·거래량. 같은 상품 ID의 거래 가능 횟수·묶음별 변형을 나눠 제공합니다.")
public record MarketItemHistoryResponse(
    long itemId,
    @Schema(description = "서버가 공식 상세를 읽은 UTC 시각. 캐시 적중 시 유지") Instant observedAt,
    @Schema(description = "부분 집계 판단 기준일(observedAt의 한국 날짜). 이 날짜 이후 값은 partial=true")
        LocalDate baseDate,
    List<MarketItemVariantResponse> variants) {
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static MarketItemHistoryResponse from(MarketItemHistoryResult result) {
    var today = LocalDate.ofInstant(result.observedAt(), KST);
    return new MarketItemHistoryResponse(
        result.history().itemId(),
        result.observedAt(),
        today,
        result.history().variants().stream()
            .map(variant -> MarketItemVariantResponse.from(variant, today))
            .toList());
  }

  public record MarketItemVariantResponse(
      @NullableResponse String name,
      @Schema(description = "남은 거래 가능 횟수. 공식 값이 없으면 null") @NullableResponse
          Integer tradeRemainCount,
      @NullableResponse Integer bundleCount,
      @Schema(description = "최신순 일별 시세") List<MarketDailyStatResponse> days,
      MarketItemSummaryResponse summary) {
    static MarketItemVariantResponse from(MarketItemHistory.Variant variant, LocalDate today) {
      var summary = variant.summary(today);
      return new MarketItemVariantResponse(
          variant.name(),
          variant.tradeRemainCount(),
          variant.bundleCount(),
          variant.days().stream()
              .map(
                  day ->
                      new MarketDailyStatResponse(
                          day.date(),
                          day.averagePrice(),
                          day.tradeCount(),
                          !day.date().isBefore(today)))
              .toList(),
          new MarketItemSummaryResponse(
              summary.latestDate(),
              summary.latestAveragePrice(),
              summary.previousAveragePrice(),
              summary.dayChangeRate(),
              summary.sevenDayDays(),
              summary.sevenDayAveragePrice(),
              summary.sevenDayChangeRate(),
              summary.sevenDayTradeCount()));
    }
  }

  public record MarketDailyStatResponse(
      LocalDate date,
      @Schema(description = "공식 일별 평균 거래가(묶음 기준)") BigDecimal averagePrice,
      long tradeCount,
      @Schema(description = "오늘(진행 중) 부분 집계 여부") boolean partial) {}

  @Schema(description = "완료일만 사용한 요약. 등락률은 % 단위(소수 둘째 자리)")
  public record MarketItemSummaryResponse(
      @Schema(description = "가장 최근 완료일") @NullableResponse LocalDate latestDate,
      @NullableResponse BigDecimal latestAveragePrice,
      @Schema(description = "그 전 완료일 평균가") @NullableResponse BigDecimal previousAveragePrice,
      @Schema(description = "전일 대비 등락률 %") @NullableResponse BigDecimal dayChangeRate,
      @Schema(description = "7일 평균에 쓴 완료일 수(최대 7)") int sevenDayDays,
      @Schema(description = "최근 완료일 최대 7일의 거래량 가중 평균가") @NullableResponse
          BigDecimal sevenDayAveragePrice,
      @Schema(description = "7일 가중 평균 대비 최근 완료일 등락률 %") @NullableResponse
          BigDecimal sevenDayChangeRate,
      long sevenDayTradeCount) {}
}
