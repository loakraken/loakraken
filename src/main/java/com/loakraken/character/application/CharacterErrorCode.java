package com.loakraken.character.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CharacterErrorCode implements ErrorCode {
  CHARACTER_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터를 찾을 수 없습니다."),
  LOST_ARK_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "캐릭터 조회 서비스가 준비되지 않았습니다."),
  LOST_ARK_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "조회 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
  LOST_ARK_API_ERROR(HttpStatus.BAD_GATEWAY, "로스트아크 API 응답을 처리할 수 없습니다."),
  LOST_ARK_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "로스트아크 API 응답이 지연되고 있습니다."),
  CHARACTER_CACHE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "캐릭터 캐시를 사용할 수 없습니다."),
  CHARACTER_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "캐릭터 조회 결과를 저장할 수 없습니다."),
  INVALID_SNAPSHOT_CURSOR(HttpStatus.BAD_REQUEST, "해당 캐릭터의 유효한 이력 커서가 아닙니다."),
  CHARACTER_HISTORY_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "캐릭터 관측 이력 기능이 비활성화되어 있습니다."),
  CHARACTER_HISTORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "캐릭터 관측 이력을 조회할 수 없습니다."),
  CHARACTER_VIEWS_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "캐릭터 조회수 기능이 비활성화되어 있습니다."),
  CHARACTER_VIEWS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "캐릭터 조회수를 조회할 수 없습니다."),
  CARD_COLLECTION_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "원정대 카드 기록 기능이 비활성화되어 있습니다."),
  CARD_COLLECTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "원정대 카드 기록을 조회할 수 없습니다."),
  CHARACTER_POPULAR_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "인기 검색 기능이 비활성화되어 있습니다."),
  CHARACTER_POPULAR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "인기 검색을 조회할 수 없습니다."),
  /** 공개 요약(#145): 캐시에도 저장된 관측에도 없다. 공식 API는 부르지 않는다. */
  CHARACTER_NOT_CACHED(HttpStatus.NOT_FOUND, "아직 확인한 기록이 없는 캐릭터입니다."),
  /** 전투력 시뮬레이터: 캐시가 바뀌어 바꿀 대상(장비 순번·보석 slot 등)이 캐릭터에 없다. 웹은 다시 불러오기를 띄운다. */
  CHANGE_TARGET_NOT_FOUND(HttpStatus.CONFLICT, "캐릭터 정보가 바뀌어 바꿀 대상을 찾지 못했습니다. 다시 불러와 주세요."),
  CARD_COLLECTION_REFRESH_COOLDOWN(
      HttpStatus.TOO_MANY_REQUESTS, "원정대 기록은 10분에 한 번 채울 수 있습니다. 잠시 후 다시 시도해 주세요.");

  private final HttpStatus httpStatus;
  private final String message;

  CharacterErrorCode(HttpStatus httpStatus, String message) {
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
