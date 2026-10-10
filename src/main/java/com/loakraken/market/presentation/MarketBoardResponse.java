package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.MarketBoard;
import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketTab;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Schema(description = "시세 보드 한 페이지. 가격은 모두 묶음 기준 골드입니다.")
public record MarketBoardResponse(
    String ruleVersion,
    @Schema(description = "등락률 계산 기준 설명") String changeBasis,
    MarketTab tab,
    String tabLabel,
    @Schema(description = "적용한 티어. null이면 티어 조건 없음") @NullableResponse Integer tier,
    String sort,
    int page,
    int pageSize,
    @Schema(description = "보드에 쓴 공식 페이지 중 가장 이른 수집 시각(UTC)") Instant observedAt,
    @Schema(description = "공식 검색의 탭 전체 품목 수") int totalItems,
    @Schema(description = "정렬에 사용한 품목 수. rise/fall은 스캔 품목 수, 그 외는 이 페이지 품목 수") int scannedItems,
    @Schema(description = "rise/fall이 탭 전체를 스캔했는지. 스캔 상한(10페이지)을 넘으면 false") boolean complete,
    List<MarketBoardItemResponse> items) {

  static MarketBoardResponse from(MarketBoard board) {
    return new MarketBoardResponse(
        MarketBoardRules.RULE_VERSION,
        MarketBoardRules.CHANGE_BASIS,
        board.tab(),
        board.tab().label(),
        board.tier(),
        board.sort().name().toLowerCase(Locale.ROOT),
        board.page(),
        MarketBoardService.PAGE_SIZE,
        board.observedAt(),
        board.totalItems(),
        board.scannedItems(),
        board.complete(),
        board.rows().stream().map(MarketBoardItemResponse::from).toList());
  }

  public record MarketBoardItemResponse(
      @Schema(description = "공식 상품 ID. 상세는 /api/v1/markets/items/{itemId}/history") long itemId,
      String name,
      @NullableResponse String icon,
      @NullableResponse String grade,
      int bundleCount,
      @NullableResponse Integer tradeRemainCount,
      @NullableResponse BigDecimal currentMinPrice,
      @NullableResponse BigDecimal recentPrice,
      @NullableResponse BigDecimal yesterdayAvgPrice,
      @Schema(description = "전일 평균가 대비 현재 최저가 등락률 %. 전일 평균 0·없음 또는 최저가 없음이면 null") @NullableResponse
          BigDecimal changeDay,
      @Schema(description = "7일 가중 평균가 대비 등락률 %. 이력 캐시가 없으면 null") @NullableResponse
          BigDecimal changeWeek,
      @Schema(description = "최근 완료일 최대 7일 평균가(오래된 날부터). 이력 캐시가 없으면 빈 배열")
          List<MarketSparkPointResponse> spark7,
      @Schema(description = "이력 캐시로 changeWeek·spark7을 채웠는지") boolean historyAvailable) {
    static MarketBoardItemResponse from(MarketBoard.Row row) {
      var price = row.price();
      return new MarketBoardItemResponse(
          price.id(),
          price.name(),
          price.icon(),
          price.grade(),
          price.bundleCount(),
          price.tradeRemainCount(),
          price.currentMinPrice(),
          price.recentPrice(),
          price.yDayAvgPrice(),
          row.changeDay(),
          row.changeWeek(),
          row.spark7().stream()
              .map(point -> new MarketSparkPointResponse(point.date(), point.averagePrice()))
              .toList(),
          row.historyAvailable());
    }
  }

  public record MarketSparkPointResponse(LocalDate date, BigDecimal averagePrice) {}
}
