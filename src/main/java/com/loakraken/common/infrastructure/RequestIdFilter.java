package com.loakraken.common.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 상관 ID(#143). 웹(web #159)이 넘긴 {@value #HEADER}가 형식에 맞으면 그대로 쓰고, 아니면 새로 만든다. 로그 MDC({@code
 * requestId}, {@code entryPoint=request})와 응답 헤더로 돌려준다. 운영 구조화 로그(ECS)는 MDC를 필드로 내보낸다.
 *
 * <p>가장 먼저 실행해 유입 제한 거절(429)에도 ID가 붙는다. 클라이언트 IP는 MDC에 넣지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Request-Id";
  static final String MDC_REQUEST_ID = "requestId";
  static final String MDC_ENTRY_POINT = "entryPoint";
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = requestId(request.getHeader(HEADER));
    MDC.put(MDC_REQUEST_ID, requestId);
    MDC.put(MDC_ENTRY_POINT, "request");
    response.setHeader(HEADER, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_REQUEST_ID);
      MDC.remove(MDC_ENTRY_POINT);
    }
  }

  static String requestId(String received) {
    if (received != null && VALID.matcher(received).matches()) return received;
    return UUID.randomUUID().toString();
  }
}
