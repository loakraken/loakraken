package com.loakraken.common.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class BusinessException extends RuntimeException {
  private final ErrorCode errorCode;
  private final Duration retryAfter;

  public BusinessException(ErrorCode errorCode) {
    this(errorCode, null);
  }

  /** retryAfter가 있으면 응답에 Retry-After(초, 올림) 헤더를 붙인다. */
  public BusinessException(ErrorCode errorCode, Duration retryAfter) {
    super(Objects.requireNonNull(errorCode).message());
    this.errorCode = errorCode;
    this.retryAfter = retryAfter;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
