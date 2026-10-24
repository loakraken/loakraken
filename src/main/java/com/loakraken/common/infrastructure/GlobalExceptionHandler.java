package com.loakraken.common.infrastructure;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
    var response =
        ResponseEntity.status(errorCode.httpStatus()).contentType(MediaType.APPLICATION_JSON);
    exception
        .retryAfter()
        .ifPresent(
            retry ->
                response.header(
                    HttpHeaders.RETRY_AFTER,
                    String.valueOf(Math.max(1, (retry.toMillis() + 999) / 1000))));
    return response.body(ApiResponse.from(errorCode));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleConstraintViolation() {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.from(CommonErrorCode.INVALID_REQUEST));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // 길이를 알리지 않은 본문이 상한을 넘으면 읽기 예외(400 경로)로 오므로 413으로 바꾼다(RequestBodyLimitFilter).
    if (tooLarge(exception)) {
      status = CommonErrorCode.REQUEST_TOO_LARGE.httpStatus();
      headers = new HttpHeaders(headers);
      headers.set(HttpHeaders.CONNECTION, "close");
    }
    var errorCode =
        switch (status.value()) {
          case 400 -> CommonErrorCode.INVALID_REQUEST;
          case 404 -> CommonErrorCode.RESOURCE_NOT_FOUND;
          case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
          case 406 -> CommonErrorCode.NOT_ACCEPTABLE;
          case 413 -> CommonErrorCode.REQUEST_TOO_LARGE;
          case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
          default -> null;
        };
    var response =
        errorCode == null
            ? new ApiResponse<Void>("HTTP_" + status.value(), "요청을 처리할 수 없습니다.", null)
            : ApiResponse.from(errorCode);
    var responseHeaders = new HttpHeaders();
    responseHeaders.putAll(headers);
    responseHeaders.setContentType(MediaType.APPLICATION_JSON);
    return super.handleExceptionInternal(exception, response, responseHeaders, status, request);
  }

  private static boolean tooLarge(Throwable exception) {
    for (var cause = exception; cause != null; cause = cause.getCause())
      if (cause instanceof RequestBodyLimitFilter.BodyTooLargeException) return true;
    return false;
  }

  /** 스택에서 처음 나오는 우리 코드(com.loakraken) 위치. 없으면 맨 위 위치. */
  static String origin(Throwable exception) {
    StackTraceElement first = null;
    for (var frame : exception.getStackTrace()) {
      if (first == null) first = frame;
      if (frame.getClassName().startsWith("com.loakraken."))
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }
    return first == null
        ? "unknown"
        : first.getClassName() + "." + first.getMethodName() + ":" + first.getLineNumber();
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
    if (tooLarge(exception))
      return ResponseEntity.status(CommonErrorCode.REQUEST_TOO_LARGE.httpStatus())
          .header(HttpHeaders.CONNECTION, "close")
          .contentType(MediaType.APPLICATION_JSON)
          .body(ApiResponse.from(CommonErrorCode.REQUEST_TOO_LARGE));
    // 예외 메시지나 원인에는 외부 응답·비밀값·캐릭터 이름이 포함될 수 있어 타입과 우리 코드의 발생 위치(클래스·메서드·줄)만 기록한다(P0 10-12).
    log.error(
        "처리하지 못한 서버 오류가 발생했습니다. exceptionType={}, at={}",
        exception.getClass().getName(),
        origin(exception));
    return ResponseEntity.internalServerError()
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.from(CommonErrorCode.INTERNAL_SERVER_ERROR));
  }
}
