package com.loakraken.common.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

/** 요청 본문 상한: 길이를 알린 본문은 읽기 전에 413, 길이를 모르는 본문은 바이트로 읽든 문자로 읽든 상한을 넘는 순간 멈춘다. */
class RequestBodyLimitFilterTest {
  private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(DataSize.ofBytes(10));

  /** Content-Length를 알리지 않은(chunked) 요청. */
  private static MockHttpServletRequest unknownLength(String body) {
    var request =
        new MockHttpServletRequest("POST", "/api/v1/markets/material-prices") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    request.setCharacterEncoding("UTF-8");
    return request;
  }

  @Test
  void 길이를_알린_본문이_상한을_넘으면_다음_필터로_넘기지_않고_413이다() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/markets/material-prices");
    request.setContent("01234567890".getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          throw new AssertionError("다음 필터로 넘기면 안 된다");
        });
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("\"code\":\"REQUEST_TOO_LARGE\"");
    assertThat(response.getHeader("Connection")).isEqualTo("close");
  }

  @Test
  void 길이를_모르는_본문은_바이트로든_문자로든_상한을_넘으면_읽기를_멈춘다() throws Exception {
    var seen = new AtomicReference<HttpServletRequest>();
    filter.doFilter(
        unknownLength("01234567890"),
        new MockHttpServletResponse(),
        (req, res) -> seen.set((HttpServletRequest) req));
    assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes())
        .isInstanceOf(RequestBodyLimitFilter.BodyTooLargeException.class);

    filter.doFilter(
        unknownLength("가나다라마"),
        new MockHttpServletResponse(),
        (req, res) -> seen.set((HttpServletRequest) req));
    assertThatThrownBy(() -> seen.get().getReader().read(new char[100]))
        .isInstanceOf(RequestBodyLimitFilter.BodyTooLargeException.class);
  }

  @Test
  void 상한_안의_본문은_그대로_읽힌다() throws Exception {
    var seen = new AtomicReference<HttpServletRequest>();
    filter.doFilter(
        unknownLength("0123456789"),
        new MockHttpServletResponse(),
        (req, res) -> seen.set((HttpServletRequest) req));
    assertThat(new String(seen.get().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo("0123456789");
  }
}
