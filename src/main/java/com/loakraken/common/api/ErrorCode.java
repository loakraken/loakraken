package com.loakraken.common.api;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
  HttpStatus httpStatus();

  String code();

  String message();
}
