package com.loakraken.stats.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum StatsErrorCode implements ErrorCode {
  STATS_INVALID_RANGE(HttpStatus.BAD_REQUEST, "최솟값이 최댓값보다 큽니다."),
  STATS_TOO_MANY_BINS(HttpStatus.BAD_REQUEST, "구간 폭이 너무 작습니다. 구간 폭을 늘려 주세요."),
  /** 고정 조건의 각인·카드 세트가 고정 없는 빌드 통계 칸에 없는 이름이다. */
  STATS_UNKNOWN_FIX(HttpStatus.BAD_REQUEST, "빌드 통계에 없는 각인이나 카드 세트로는 고정할 수 없습니다."),
  STATS_CHARACTER_NOT_OBSERVED(
      HttpStatus.NOT_FOUND, "아직 LOAKraken에 기록되지 않은 캐릭터입니다. 캐릭터를 먼저 검색해 주세요."),
  STATS_GUILD_NOT_OBSERVED(
      HttpStatus.NOT_FOUND, "아직 LOAKraken에 기록된 길드원이 없는 길드입니다. 길드원 캐릭터를 먼저 검색해 주세요."),
  STATS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "통계를 조회할 수 없습니다.");

  private final HttpStatus httpStatus;
  private final String message;

  StatsErrorCode(HttpStatus httpStatus, String message) {
    this.httpStatus = httpStatus;
    this.message = message;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  public String code() {
    return name();
  }

  public String message() {
    return message;
  }
}
