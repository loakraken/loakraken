package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionSearchPort;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 보석 매물 직업·스킬 필터 스위치(#542): 끄면 목록·조건 붙은 매물이 404이고 공식 호출이 없다. 조건 없는 매물은 그대로. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "market.gem-filter.enabled=false")
class GemFilterDisabledIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean AuctionSearchPort search;

  @Test
  void 필터를_끄면_목록과_조건_붙은_매물이_404이고_공식_호출이_없다() throws Exception {
    var skills = get("/api/v1/markets/auctions/gems/skills");
    assertThat(skills.statusCode()).isEqualTo(404);
    assertThat(skills.body()).contains("GEM_FILTER_DISABLED");
    var filtered =
        get(
            "/api/v1/markets/auctions/gems/listings?kind="
                + encode("겁화")
                + "&level=10&className="
                + encode("데모닉"));
    assertThat(filtered.statusCode()).isEqualTo(404);
    assertThat(filtered.body()).contains("GEM_FILTER_DISABLED");
    verifyNoInteractions(search);

    when(search.search(any())).thenReturn(new AuctionPage(0, List.of(), Instant.now()));
    var plain =
        get("/api/v1/markets/auctions/gems/listings?kind=" + encode("겁화") + "&level=10");
    assertThat(plain.statusCode()).isEqualTo(200);
    verify(search, times(1)).search(any());
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
