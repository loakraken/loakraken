package com.loakraken.common.infrastructure;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
    var errorCode = exception.errorCode();
    return ResponseEntity.status(errorCode.httpStatus()).body(ApiResponse.from(errorCode));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleConstraintViolation() {
    return ResponseEntity.badRequest().body(ApiResponse.from(CommonErrorCode.INVALID_REQUEST));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var errorCode =
        switch (status.value()) {
          case 400 -> CommonErrorCode.INVALID_REQUEST;
          case 404 -> CommonErrorCode.RESOURCE_NOT_FOUND;
          case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
          case 406 -> CommonErrorCode.NOT_ACCEPTABLE;
          case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
          default -> null;
        };
    var response =
        errorCode == null
            ? new ApiResponse<Void>("HTTP_" + status.value(), "요청을 처리할 수 없습니다.", null)
            : ApiResponse.from(errorCode);
    return super.handleExceptionInternal(exception, response, headers, status, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
    // 예외 메시지나 원인에는 외부 응답·비밀값이 포함될 수 있어 타입만 기록한다.
    log.error("처리하지 못한 서버 오류가 발생했습니다. exceptionType={}", exception.getClass().getName());
    return ResponseEntity.internalServerError()
        .body(ApiResponse.from(CommonErrorCode.INTERNAL_SERVER_ERROR));
  }
}
