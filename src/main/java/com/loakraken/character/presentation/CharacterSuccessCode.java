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
  CHARACTER_POPULAR_SUCCESS;

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
      default -> "캐릭터 조회에 성공했습니다.";
    };
  }
}
