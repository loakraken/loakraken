package com.loakraken.market.domain;

import java.time.LocalDate;

/**
 * 품목 하나의 완료일 거래량(공식 상품 상세의 일별 TradeCount). volumeDate = 가장 최근 완료일(전일), yesterday = 그날 거래량, sevenDay
 * = 최근 완료일 최대 7일 합. 완료일 값이라 하루 동안 바뀌지 않는다.
 */
public record MarketVolume(long itemId, LocalDate volumeDate, long yesterday, long sevenDay) {}
