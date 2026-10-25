package com.loakraken.common.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 본문 크기 상한. JSON 본문은 Tomcat의 폼 크기 상한을 받지 않으므로 여기서 막는다. Content-Length가 상한을 넘으면 본문을 읽기 전에 413
 * REQUEST_TOO_LARGE다. 길이를 알리지 않는 본문(chunked)은 읽는 만큼 세다가 상한을 넘으면 {@link BodyTooLargeException}으로 읽기를
 * 멈추고, 예외 처리기가 같은 413으로 바꾼다.
 *
 * <p>요청 ID 다음, 유입 제한보다 먼저 실행해 큰 본문이 클라이언트 한도를 쓰지 않게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestBodyLimitFilter extends OncePerRequestFilter {
  private final long limit;

  RequestBodyLimitFilter(@Value("${http.max-request-body:64KB}") DataSize limit) {
    if (limit.toBytes() < 1) throw new IllegalArgumentException("http.max-request-body는 1바이트 이상");
    this.limit = limit.toBytes();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > limit) {
      reject(response);
      return;
    }
    chain.doFilter(new Limited(request, limit), response);
  }

  static void reject(HttpServletResponse response) throws IOException {
    var code = CommonErrorCode.REQUEST_TOO_LARGE;
    response.setStatus(code.httpStatus().value());
    // 남은 본문을 읽지 않으므로 연결을 다시 쓰지 않는다.
    response.setHeader("Connection", "close");
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

  /** 본문이 상한을 넘었다. 메시지에 본문 내용은 없다. */
  static final class BodyTooLargeException extends IOException {
    BodyTooLargeException() {
      super("request body over limit");
    }
  }

  private static final class Limited extends HttpServletRequestWrapper {
    private final long limit;
    private ServletInputStream stream;

    Limited(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) stream = new Counting(super.getInputStream(), limit);
      return stream;
    }

    /** 문자로 읽어도 같은 계수 스트림을 지나게 한다(원래 reader로 상한을 우회하지 않게). */
    @Override
    public java.io.BufferedReader getReader() throws IOException {
      var encoding = getCharacterEncoding();
      java.nio.charset.Charset charset;
      try {
        charset =
            encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding);
      } catch (IllegalArgumentException exception) {
        throw new java.io.UnsupportedEncodingException(encoding);
      }
      return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class Counting extends ServletInputStream {
    private final ServletInputStream delegate;
    private final long limit;
    private long read;

    Counting(ServletInputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value >= 0) count(1);
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = delegate.read(buffer, offset, length);
      if (n > 0) count(n);
      return n;
    }

    private void count(int n) throws BodyTooLargeException {
      read += n;
      if (read > limit) throw new BodyTooLargeException();
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }
  }
}
