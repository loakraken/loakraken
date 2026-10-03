package com.loakraken.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공통 API 응답. 오류 응답의 data는 null입니다.")
public record ApiResponse<T>(
    @Schema(description = "업무 결과 코드", example = "CHARACTER_SEARCH_SUCCESS") String code,
    @Schema(description = "공개용 안내 메시지", example = "캐릭터 조회에 성공했습니다.") String message,
    @Schema(description = "성공 응답 데이터. 오류일 때 null") T data) {
  public static <T> ApiResponse<T> of(SuccessCode successCode, T data) {
    return new ApiResponse<>(successCode.code(), successCode.message(), data);
  }

  public static ApiResponse<Void> from(ErrorCode errorCode) {
    return new ApiResponse<>(errorCode.code(), errorCode.message(), null);
  }
}
