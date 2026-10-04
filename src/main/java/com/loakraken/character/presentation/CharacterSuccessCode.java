package com.loakraken.character.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum CharacterSuccessCode implements SuccessCode {
  CHARACTER_SEARCH_SUCCESS,
  CHARACTER_HISTORY_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return this == CHARACTER_HISTORY_SUCCESS ? "캐릭터 관측 이력 조회에 성공했습니다." : "캐릭터 조회에 성공했습니다.";
  }
}
