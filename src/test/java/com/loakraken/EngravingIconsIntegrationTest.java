package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.MarketCache;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 각인 그림 목록: 저장해 둔 유물 각인서 아이콘만으로 답하고(공식 호출 없음), 공식 이미지 서버 주소만 준다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngravingIconsIntegrationTest {
  private static final String CDN = "https://cdn-lostark.game.onstove.com/";
  @LocalServerPort int port;
  @Autowired MarketCache marketCache;
  @Autowired StringRedisTemplate redis;

  @BeforeEach
  @AfterEach
  void 정리() {
    redis.delete("market:v1:relic-books");
    redis.delete("market:v1:relic-book-icons");
  }

  @Test
  void 저장된_아이콘으로_공백_뺀_각인_이름과_공식_주소를_이름순으로_준다() throws Exception {
    var empty = get();
    assertThat(empty.statusCode()).isEqualTo(200);
    assertThat(new JsonMapper().readTree(empty.body()).at("/data/icons").size()).isZero();

    marketCache.saveRelicBookIcons(
        Map.of(
            "유물원한각인서", CDN + "efm/a.png",
            "유물예리한둔기각인서", CDN + "efm/b.png",
            "유물아드레날린각인서", "https://elsewhere.example/c.png"));
    var response = get();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var json = new JsonMapper().readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("ENGRAVING_ICONS_SUCCESS");
    var icons = json.at("/data/icons");
    assertThat(icons.size()).isEqualTo(2);
    assertThat(icons.at("/0/key").asText()).isEqualTo("예리한둔기");
    assertThat(icons.at("/0/icon").asText()).isEqualTo(CDN + "efm/b.png");
    assertThat(icons.at("/1/key").asText()).isEqualTo("원한");
    ApiContractFixture.write("market-engraving-icons.json", response.body());
  }

  private HttpResponse<String> get() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + port + "/api/v1/markets/engraving-icons"))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
