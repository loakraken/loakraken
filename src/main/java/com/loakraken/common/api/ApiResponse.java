package com.loakraken.common.api;

public record ApiResponse<T>(String code, String message, T data) {
  public static <T> ApiResponse<T> of(SuccessCode successCode, T data) {
    return new ApiResponse<>(successCode.code(), successCode.message(), data);
  }

  public static ApiResponse<Void> from(ErrorCode errorCode) {
    return new ApiResponse<>(errorCode.code(), errorCode.message(), null);
  }
}
