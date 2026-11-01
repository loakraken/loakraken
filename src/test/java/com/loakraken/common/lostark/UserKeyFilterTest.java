package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 사용자 키(BYOK) 헤더: 꺼졌거나 web이 아니면 지우고 무시, 켜져 있고 web이면 요청 스레드에만 둔다. */
class UserKeyFilterTest {
  private static final String WEB = "172.31.250.10";
  private static final String KEY = "aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJlLW1hcmtlcg";

  private record Seen(String header, String bearer, String bearerOutsideApply) {}

  private static ByokProperties properties(boolean enabled) {
    return new ByokProperties(enabled, "172\\.31\\.250\\.10", 90, 2);
  }

  private static Seen run(
      ByokProperties properties, String peer, String key, MockHttpServletResponse response)
      throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/v1/characters/search");
    request.setRemoteAddr(peer);
    if (key != null) request.addHeader(UserKeyFilter.HEADER, key);
    var seen = new AtomicReference<Seen>();
    new UserKeyFilter(properties, ClientLimiter.DISABLED)
        .doFilter(
            request,
            response,
            (req, res) ->
                seen.set(
                    new Seen(
                        ((jakarta.servlet.http.HttpServletRequest) req)
                            .getHeader(UserKeyFilter.HEADER),
                        UserKeyContext.apply(UserKeyContext::bearer),
                        UserKeyContext.bearer())));
    return seen.get();
  }

  @Test
  void 꺼져_있으면_헤더를_지우고_쓰지_않는다() throws Exception {
    var seen = run(properties(false), WEB, KEY, new MockHttpServletResponse());
    assertThat(seen.header()).isNull();
    assertThat(seen.bearer()).isNull();
  }

  @Test
  void web이_아닌_상대가_보낸_헤더는_지우고_쓰지_않는다() throws Exception {
    var seen = run(properties(true), "203.0.113.7", KEY, new MockHttpServletResponse());
    assertThat(seen.header()).isNull();
    assertThat(seen.bearer()).isNull();
    // 믿는 주소 설정이 비면 아무도 믿지 않는다.
    var none = run(new ByokProperties(true, "", 90, 2), WEB, KEY, new MockHttpServletResponse());
    assertThat(none.bearer()).isNull();
  }

  @Test
  void 켜져_있고_web이_보내면_apply_안에서만_쓰고_요청이_끝나면_지운다() throws Exception {
    var seen = run(properties(true), WEB, KEY, new MockHttpServletResponse());
    assertThat(seen.header()).as("뒤 단계는 헤더를 보지 못한다").isNull();
    assertThat(seen.bearer()).isEqualTo(KEY);
    assertThat(seen.bearerOutsideApply()).as("apply 밖에서는 쓰지 않는다").isNull();
    assertThat(UserKeyContext.apply(UserKeyContext::bearer)).isNull();
  }

  @Test
  void 형식이_틀리면_400이고_값을_응답에_담지_않는다() throws Exception {
    for (var bad :
        new String[] {
          "not-a-jwt", "a.b", "a.b.c d", "a.b." + "x".repeat(UserKeyFilter.MAX_LENGTH)
        }) {
      var response = new MockHttpServletResponse();
      var seen = run(properties(true), WEB, bad, response);
      assertThat(seen).as(bad).isNull();
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentAsString())
          .contains("USER_KEY_MALFORMED")
          .doesNotContain(bad.substring(0, Math.min(8, bad.length())));
    }
  }

  @Test
  void 다른_스레드는_요청의_키를_보지_못한다() throws Exception {
    var other = new AtomicReference<String>("unset");
    var request = new MockHttpServletRequest("GET", "/api/v1/characters/search");
    request.setRemoteAddr(WEB);
    request.addHeader(UserKeyFilter.HEADER, KEY);
    new UserKeyFilter(properties(true), ClientLimiter.DISABLED)
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (req, res) ->
                UserKeyContext.apply(
                    () -> {
                      var thread =
                          Thread.ofVirtual().start(() -> other.set(UserKeyContext.bearer()));
                      try {
                        thread.join();
                      } catch (InterruptedException exception) {
                        throw new IllegalStateException(exception);
                      }
                      return null;
                    }));
    assertThat(other.get()).isNull();
  }
}
