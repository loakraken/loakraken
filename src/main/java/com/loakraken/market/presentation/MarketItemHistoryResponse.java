package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.OptionalResponse;
import com.loakraken.market.application.MarketHistoryRangeService;
import com.loakraken.market.application.MarketItemHistoryResult;
import com.loakraken.market.domain.MarketItemHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Schema(
    description =
        "공식 거래소 상품의 일별 평균가·거래량. 기본(range=14d)은 공식 최근 14일이고, 30d 이상은 저장한 완료일을 더합니다. 같은 상품 ID의 거래 가능 횟수·묶음별 변형을 나눠 제공합니다.")
public record MarketItemHistoryResponse(
    long itemId,
    @Schema(description = "서버가 공식 상세를 읽은 UTC 시각. 캐시 적중 시 유지") Instant observedAt,
    @Schema(description = "부분 집계 판단 기준일(observedAt의 한국 날짜). 이 날짜 이후 값은 partial=true")
        LocalDate baseDate,
    List<MarketItemVariantResponse> variants,
    @Schema(description = "적용한 기간(7d·14d·30d·90d·365d). 늘 보냄", example = "14d") @OptionalResponse
        String range) {
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static MarketItemHistoryResponse from(MarketItemHistoryResult result) {
    var today = LocalDate.ofInstant(result.observedAt(), KST);
    return new MarketItemHistoryResponse(
        result.history().itemId(),
        result.observedAt(),
        today,
        result.history().variants().stream()
            .map(variant -> MarketItemVariantResponse.from(variant, today))
            .toList(),
        "14d");
  }

  /**
   * 기간 조회. 7d는 공식 14일 중 기준일 포함 7일, 14d는 지금과 같음, 30d 이상은 공식 14일에 저장 완료일(같은 변형 칸, 공식에 없는 날짜만)을 더하고
   * recordedSince·storedDays를 채운다. 공식 응답에 없는 변형 칸의 저장 행은 붙이지 않는다.
   */
  static MarketItemHistoryResponse from(MarketHistoryRangeService.ItemRange ranged) {
    var base = from(ranged.base());
    var range = ranged.range();
    var from = range.from(base.baseDate());
    var stored = ranged.stored();
    var variants = new java.util.ArrayList<MarketItemVariantResponse>();
    var officialVariants = ranged.base().history().variants();
    for (int i = 0; i < officialVariants.size(); i++) {
      var variant = base.variants().get(i);
      var key =
          com.loakraken.market.application.MarketDailyStore.variantKey(
              officialVariants.get(i).tradeRemainCount(), officialVariants.get(i).bundleCount());
      var days = new java.util.ArrayList<MarketDailyStatResponse>();
      var officialDates = new java.util.HashSet<LocalDate>();
      for (var day : variant.days()) {
        officialDates.add(day.date());
        if (!day.date().isBefore(from)) days.add(day);
      }
      LocalDate recordedSince = null;
      Integer storedDays = null;
      if (stored != null) {
        int count = 0;
        for (var day : stored.days()) {
          if (!day.variantKey().equals(key) || day.date().isBefore(from)) continue;
          count++;
          if (!officialDates.contains(day.date()))
            days.add(
                new MarketDailyStatResponse(
                    day.date(), day.averagePrice(), day.tradeCount(), false));
        }
        storedDays = count;
        recordedSince = stored.firstDates().get(key);
      }
      days.sort(java.util.Comparator.comparing(MarketDailyStatResponse::date).reversed());
      variants.add(
          new MarketItemVariantResponse(
              variant.name(),
              variant.tradeRemainCount(),
              variant.bundleCount(),
              days,
              variant.summary(),
              recordedSince,
              storedDays));
    }
    return new MarketItemHistoryResponse(
        base.itemId(), base.observedAt(), base.baseDate(), variants, range.value());
  }

  public record MarketItemVariantResponse(
      @NullableResponse String name,
      @Schema(description = "남은 거래 가능 횟수. 공식 값이 없으면 null") @NullableResponse
          Integer tradeRemainCount,
      @NullableResponse Integer bundleCount,
      @Schema(description = "최신순 일별 시세. 30d 이상은 저장한 완료일 포함(같은 날짜는 공식 값), 저장 없는 날은 행 없음")
          List<MarketDailyStatResponse> days,
      MarketItemSummaryResponse summary,
      @Schema(description = "이 변형을 처음 저장한 날짜(기간과 상관없이). 30d 이상에서만, 저장 행이 없으면 null")
          @OptionalResponse
          @NullableResponse
          LocalDate recordedSince,
      @Schema(description = "기간 안 저장 완료일 수. 30d 이상에서만, 아니면 null")
          @OptionalResponse
          @NullableResponse
          Integer storedDays) {
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
              summary.sevenDayTradeCount()),
          null,
          null);
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
