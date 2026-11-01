package com.loakraken.common.lostark;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 사용자 공식 API 키(BYOK) 헤더 {@value #HEADER}를 받는다. {@link ClientLimitFilter} 뒤에 돌아, 형식 오류 응답도 클라이언트 요청
 * 한도(GENERAL)에 이미 세어져 있다(키가 맞는지 대량으로 알아보는 데 쓰이지 않게).
 *
 * <ul>
 *   <li>기능이 꺼졌거나 연결 상대가 믿는 web이 아니면 헤더를 지운 요청으로 넘긴다(값은 읽지 않는다).
 *   <li>켜져 있고 web이 보냈으면 형식(점 세 조각, 길이 상한)만 보고 요청 스레드에 둔다. 틀리면 400 USER_KEY_MALFORMED(값은 응답에 없음).
 *   <li>요청이 끝나면 지운다. 이후 단계는 헤더를 보지 못하고 {@link UserKeyContext}로만 쓴다.
 * </ul>
 */
class UserKeyFilter extends OncePerRequestFilter {
  static final String HEADER = "X-LK-User-Key";
  static final int MAX_LENGTH = 2048;

  /** 공식 키는 JWT 모양(base64url 세 조각)이다. */
  private static final Pattern SHAPE =
      Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

  private final ByokProperties properties;
  private final ClientLimiter limiter;

  UserKeyFilter(ByokProperties properties, ClientLimiter limiter) {
    this.properties = properties;
    this.limiter = limiter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getHeader(HEADER) == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var hidden = new WithoutKeyHeader(request);
    if (!properties.enabled() || !properties.trusted(ClientLimitFilter.peerAddress(request))) {
      chain.doFilter(hidden, response);
      return;
    }
    var values = Collections.list(request.getHeaders(HEADER));
    var key = values.size() == 1 ? values.getFirst().strip() : "";
    if (key.isEmpty() || key.length() > MAX_LENGTH || !SHAPE.matcher(key).matches()) {
      malformed(response);
      return;
    }
    UserKeyContext.enter(key, limiter.clientKey("byok:" + key));
    try {
      chain.doFilter(hidden, response);
    } finally {
      UserKeyContext.exit();
    }
  }

  private static void malformed(HttpServletResponse response) throws IOException {
    var code = LostArkErrorCode.USER_KEY_MALFORMED;
    response.setStatus(code.httpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"code\":\""
                + code.code()
                + "\",\"message\":\""
                + code.message()
                + "\",\"data\":null}");
  }

  /** 키 헤더를 보이지 않게 한 요청(뒤 단계의 로그·예외 처리가 값을 볼 수 없게). */
  static final class WithoutKeyHeader extends HttpServletRequestWrapper {
    WithoutKeyHeader(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getHeader(String name) {
      return HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return HEADER.equalsIgnoreCase(name)
          ? Collections.emptyEnumeration()
          : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      var names = Collections.list(super.getHeaderNames());
      names.removeIf(HEADER::equalsIgnoreCase);
      return Collections.enumeration(names);
    }
  }
}
