package com.loakraken.specup.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum SpecUpErrorCode implements ErrorCode {
  /** 플래그가 꺼져 있다(PM-프론트 16:27 ⑦: 캐시 없음 CHARACTER_NOT_CACHED와 구분해 웹이 메뉴를 숨긴다). */
  SPEC_UP_DISABLED(HttpStatus.NOT_FOUND, "스펙업 가이드는 아직 열리지 않았습니다."),
  /** 목표 전투력이 지금 전투력의 2배를 넘는다(L31 계산 상한). */
  SPEC_UP_GOAL_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "목표 전투력은 지금 전투력의 2배 이하로 입력해 주세요."),
  /**
   * 줄 상세(#544): 지금 캐릭터 캐시·시세로 만든 목록에 그 후보가 없다(세팅이 바뀜), 또는 시세가 연달아 바뀌어 목록 비용과 내역 금액을 두 번 맞추지
   * 못했다(#554). 웹은 목록을 다시 부른다.
   */
  SPEC_UP_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "이 후보가 바뀌었습니다. 목록을 새로 불러 주세요."),
  /** 추천 순서(이어 고르기) 계산 자리가 모두 차 있다. 기다리지 않고 바로 돌려준다(Retry-After). */
  SPEC_UP_BUSY(HttpStatus.TOO_MANY_REQUESTS, "추천 순서 요청이 몰려 지금 계산하지 못했습니다. 잠시 뒤 다시 요청해 주세요.");

  private final HttpStatus httpStatus;
  private final String message;

  SpecUpErrorCode(HttpStatus httpStatus, String message) {
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
