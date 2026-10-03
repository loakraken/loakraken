package com.loakraken.common.api;

import org.springframework.http.HttpStatus;

public interface SuccessCode {
  HttpStatus httpStatus();

  String code();

  String message();
}
