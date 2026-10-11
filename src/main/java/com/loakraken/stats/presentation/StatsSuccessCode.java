package com.loakraken.stats.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum StatsSuccessCode implements SuccessCode {
  STATS_SUMMARY_SUCCESS,
  STATS_CLASSES_SUCCESS,
  STATS_SERVERS_SUCCESS,
  STATS_DISTRIBUTION_SUCCESS,
  STATS_SNAPSHOTS_SUCCESS,
  RANKING_SUCCESS,
  COLLECTIBLE_RANKING_SUCCESS,
  ROSTER_RANKING_SUCCESS,
  CHARACTER_RANK_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return switch (this) {
      case RANKING_SUCCESS -> "랭킹 조회에 성공했습니다.";
      case COLLECTIBLE_RANKING_SUCCESS -> "수집품 랭킹 조회에 성공했습니다.";
      case ROSTER_RANKING_SUCCESS -> "원정대 랭킹 조회에 성공했습니다.";
      case CHARACTER_RANK_SUCCESS -> "캐릭터 순위 조회에 성공했습니다.";
      default -> "통계 조회에 성공했습니다.";
    };
  }
}
