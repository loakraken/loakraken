package com.loakraken.common.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
  @Test
  void 형식에_맞는_요청_ID는_그대로_쓰고_MDC와_응답_헤더에_둔_뒤_지운다() throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/v1/home/news");
    request.addHeader("X-Request-Id", "web-1234:abcd.EF_9");
    var response = new MockHttpServletResponse();
    var seen = new AtomicReference<String>();
    var entry = new AtomicReference<String>();
    new RequestIdFilter()
        .doFilter(
            request,
            response,
            (req, res) -> {
              seen.set(MDC.get("requestId"));
              entry.set(MDC.get("entryPoint"));
            });
    assertThat(seen.get()).isEqualTo("web-1234:abcd.EF_9");
    assertThat(entry.get()).isEqualTo("request");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("web-1234:abcd.EF_9");
    assertThat(MDC.get("requestId")).isNull();
    assertThat(MDC.get("entryPoint")).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "short", "has space 1234", "줄바꿈\n주입시도1234", "<script>alert1</script>"})
  void 형식에_맞지_않으면_새_UUID를_만든다(String received) {
    var id = RequestIdFilter.requestId(received);
    assertThat(id).isNotEqualTo(received).matches("[0-9a-f-]{36}");
    assertThat(RequestIdFilter.requestId(null)).matches("[0-9a-f-]{36}");
    assertThat(RequestIdFilter.requestId("a".repeat(129))).hasSize(36);
  }
}
