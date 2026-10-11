package com.loakraken.character.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum CharacterSuccessCode implements SuccessCode {
  CHARACTER_SEARCH_SUCCESS,
  CHARACTER_HISTORY_SUCCESS,
  CHARACTER_SIBLINGS_SUCCESS,
  CHARACTER_CRIT_SPEED_SUCCESS,
  CHARACTER_VIEWS_SUCCESS,
  CHARACTER_CARD_COLLECTION_SUCCESS,
  CHARACTER_POPULAR_SUCCESS,
  CHARACTER_CARD_COLLECTION_REFRESH_SUCCESS,
  CHARACTER_TITLES_SUCCESS,
  CHARACTER_PUBLIC_SUMMARY_SUCCESS,
  CHARACTER_ITEM_TOOLTIPS_SUCCESS,
  CHARACTER_COMBAT_POWER_OPTIONS_SUCCESS,
  CHARACTER_COMBAT_POWER_SIMULATE_SUCCESS,
  CHARACTER_COMBAT_POWER_CONTRIBUTIONS_SUCCESS,
  CHARACTER_SUGGEST_SUCCESS,
  CHARACTER_ROSTER_COMBAT_POWER_SUCCESS,
  CHARACTER_ROSTER_COMBAT_POWER_REFRESH_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return switch (this) {
      case CHARACTER_HISTORY_SUCCESS -> "캐릭터 관측 이력 조회에 성공했습니다.";
      case CHARACTER_SIBLINGS_SUCCESS -> "원정대 캐릭터 조회에 성공했습니다.";
      case CHARACTER_CRIT_SPEED_SUCCESS -> "치명타·공이속 계산에 성공했습니다.";
      case CHARACTER_VIEWS_SUCCESS -> "캐릭터 조회수 조회에 성공했습니다.";
      case CHARACTER_CARD_COLLECTION_SUCCESS -> "원정대 카드 기록 조회에 성공했습니다.";
      case CHARACTER_POPULAR_SUCCESS -> "인기 검색 캐릭터 조회에 성공했습니다.";
      case CHARACTER_CARD_COLLECTION_REFRESH_SUCCESS -> "원정대 카드 기록을 채웠습니다.";
      case CHARACTER_TITLES_SUCCESS -> "원정대 칭호 조회에 성공했습니다.";
      case CHARACTER_PUBLIC_SUMMARY_SUCCESS -> "캐릭터 공개 요약 조회에 성공했습니다.";
      case CHARACTER_ITEM_TOOLTIPS_SUCCESS -> "아이템 툴팁 줄 조회에 성공했습니다.";
      case CHARACTER_COMBAT_POWER_OPTIONS_SUCCESS -> "전투력 시뮬레이터 입력 범위 조회에 성공했습니다.";
      case CHARACTER_COMBAT_POWER_SIMULATE_SUCCESS -> "전투력 시뮬레이션에 성공했습니다.";
      case CHARACTER_COMBAT_POWER_CONTRIBUTIONS_SUCCESS -> "전투력 기여 조회에 성공했습니다.";
      case CHARACTER_SUGGEST_SUCCESS -> "캐릭터 이름 자동완성 조회에 성공했습니다.";
      case CHARACTER_ROSTER_COMBAT_POWER_SUCCESS -> "원정대 캐릭터별 전투력 조회에 성공했습니다.";
      case CHARACTER_ROSTER_COMBAT_POWER_REFRESH_SUCCESS -> "원정대 전투력을 불러왔습니다.";
      default -> "캐릭터 조회에 성공했습니다.";
    };
  }
}
