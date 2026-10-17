package com.loakraken.alerts.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AlertErrorCode implements ErrorCode {
  ALERT_INVALID_SUBSCRIPTION(HttpStatus.BAD_REQUEST, "브라우저 알림 구독 정보가 올바르지 않습니다."),
  /** 구독이 없거나 관리 토큰이 맞지 않을 때 같은 본문이다(구독 존재 여부를 드러내지 않음). */
  ALERT_SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 구독을 찾을 수 없습니다."),
  ALERT_ITEM_NOT_ALERTABLE(HttpStatus.BAD_REQUEST, "이 품목은 시세 알림을 걸 수 없습니다."),
  ALERT_TOO_MANY_CONDITIONS(HttpStatus.BAD_REQUEST, "알림 조건이 너무 많습니다."),
  ALERT_CONDITION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 조건을 찾을 수 없습니다."),
  /** 구독할 때 보낸 확인 알림을 푸시 서비스가 받지 않았다. 구독은 저장하지 않는다. */
  ALERT_PUSH_NOT_DELIVERED(
      HttpStatus.BAD_REQUEST, "브라우저로 확인 알림을 보내지 못했습니다. 브라우저 알림 권한을 확인하고 다시 구독해 주세요."),
  /** 확인 알림이 푸시 서비스 일시 장애(429·5xx·연결 실패)로 가지 못했다. 구독은 저장하지 않는다. */
  ALERT_PUSH_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "브라우저 알림 서비스에 잠시 연결할 수 없습니다. 잠시 뒤 다시 구독해 주세요."),
  ALERTS_FULL(HttpStatus.SERVICE_UNAVAILABLE, "지금은 새 알림 구독을 받을 수 없습니다. 잠시 뒤 다시 시도해 주세요.");

  private final HttpStatus httpStatus;
  private final String message;

  AlertErrorCode(HttpStatus httpStatus, String message) {
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
