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
  TITLE_EQUIP_SUCCESS,
  COLLECTIBLE_OWNERSHIP_SUCCESS,
  COLLECTIBLE_STANDING_SUCCESS,
  ROSTER_RANKING_SUCCESS,
  GUILD_RANKING_SUCCESS,
  CHARACTER_RANK_SUCCESS,
  STATS_CLASS_BUILDS_SUCCESS,
  GUILD_DETAIL_SUCCESS,
  STATS_BEST_COMBAT_POWER_SUCCESS,
  STATS_GEM_LEVELS_SUCCESS;

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
      case TITLE_EQUIP_SUCCESS -> "칭호 장착 확인 비율 조회에 성공했습니다.";
      case COLLECTIBLE_OWNERSHIP_SUCCESS -> "수집품 보유율 조회에 성공했습니다.";
      case COLLECTIBLE_STANDING_SUCCESS -> "수집품 순위 조회에 성공했습니다.";
      case ROSTER_RANKING_SUCCESS -> "원정대 랭킹 조회에 성공했습니다.";
      case GUILD_RANKING_SUCCESS -> "길드 랭킹 조회에 성공했습니다.";
      case CHARACTER_RANK_SUCCESS -> "캐릭터 순위 조회에 성공했습니다.";
      case STATS_CLASS_BUILDS_SUCCESS -> "직업 빌드 통계 조회에 성공했습니다.";
      case GUILD_DETAIL_SUCCESS -> "길드 집계 조회에 성공했습니다.";
      case STATS_BEST_COMBAT_POWER_SUCCESS -> "최고 전투력 통계 조회에 성공했습니다.";
      case STATS_GEM_LEVELS_SUCCESS -> "보석 레벨 분포 조회에 성공했습니다.";
      default -> "통계 조회에 성공했습니다.";
    };
  }
}
