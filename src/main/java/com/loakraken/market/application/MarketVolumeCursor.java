package com.loakraken.market.application;

import java.time.LocalDate;

/**
 * 거래량 채우기 돌림 탭(품목이 많아 하루에 다 못 받는 탭, 예: 아바타)의 위치. page = 다음에 받을 공식 검색 페이지(1부터), cycleStartedOn = 이번
 * 한 바퀴를 시작한 KST 날짜. 날마다 이어서 받고, 마지막 페이지 뒤에는 1페이지로 돌아간다.
 */
public record MarketVolumeCursor(int page, LocalDate cycleStartedOn, int attempts) {
  public MarketVolumeCursor {
    if (page < 1) throw new IllegalArgumentException("page는 1 이상이어야 합니다.");
    if (attempts < 0) throw new IllegalArgumentException("attempts는 0 이상이어야 합니다.");
    java.util.Objects.requireNonNull(cycleStartedOn);
  }

  public MarketVolumeCursor(int page, LocalDate cycleStartedOn) {
    this(page, cycleStartedOn, 0);
  }
}
