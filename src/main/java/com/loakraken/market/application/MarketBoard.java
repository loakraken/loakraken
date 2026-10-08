package com.loakraken.market.application;

import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 시세 보드 한 페이지. scannedItems는 정렬에 사용한 품목 수다. */
public record MarketBoard(
    MarketTab tab,
    Integer tier,
    MarketBoardRules.Sort sort,
    int page,
    Instant observedAt,
    int totalItems,
    int scannedItems,
    boolean complete,
    List<Row> rows) {

  /** changeWeek·spark7은 이력 캐시가 있을 때만 채운다. spark7은 오래된 날부터 최대 7개 완료일이다. */
  public record Row(
      MarketPrice price,
      BigDecimal changeDay,
      BigDecimal changeWeek,
      List<SparkPoint> spark7,
      boolean historyAvailable) {}

  public record SparkPoint(LocalDate date, BigDecimal averagePrice) {}
}
