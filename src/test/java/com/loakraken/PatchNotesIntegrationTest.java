package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.loakraken.home.application.HomeSourcePort;
import com.loakraken.home.domain.NewsFeed;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PatchNotesIntegrationTest {
  private static final String BASE = "https://lostark.game.onstove.com/News/Notice/Views/";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean HomeSourcePort source;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    redis.delete(List.of("home:v1:news"));
    jdbc.sql("DELETE FROM patch_notice").update();
  }

  @Test
  void 소식을_새로_받을_때_패치_공지만_공지_번호로_쌓고_목록을_준다() throws Exception {
    var now = LocalDateTime.now();
    when(source.fetchNews())
        .thenReturn(
            new NewsFeed(
                List.of(
                    notice("10월 7일(수) 업데이트 내역 안내", now.minusDays(2), 13565),
                    notice("10월 8일(목) 클라이언트 패치 안내", now.minusDays(1), 13567),
                    notice("10월 9일(금) 로스트아크 임시 점검 완료 안내", now, 13569),
                    notice("10월 9일(금) 로스트아크 임시 점검 안내", now.minusHours(12), 13568),
                    notice("9월 23일(수) 신규 상품 및 확률 안내", now.minusDays(3), 13549)),
                List.of()))
        .thenReturn(
            new NewsFeed(
                List.of(notice("10월 7일(수) 업데이트 내역 안내 (내용 추가)", now.minusDays(2), 13565)),
                List.of()));

    // 저장은 비동기라 반영될 때까지 기다린다.
    var json = await("/api/v1/home/patch-notes", 3);
    assertThat(json.path("code").asText()).isEqualTo("HOME_PATCH_NOTES_SUCCESS");
    var data = json.path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("patch-notice-v1");
    assertThat(data.path("total").asLong()).isEqualTo(3);
    assertThat(data.at("/items/0/noticeId").asLong()).isEqualTo(13569);
    assertThat(data.at("/items/0/category").asText()).isEqualTo("MAINTENANCE_COMPLETE");
    assertThat(data.at("/items/2/category").asText()).isEqualTo("UPDATE");
    assertThat(data.at("/items/2/url").asText()).isEqualTo(BASE + 13565);
    assertThat(data.path("oldestPublishedAt").asText()).isNotBlank();
    ApiContractFixture.write("home-patch-notes.json", get("/api/v1/home/patch-notes").body());

    // 캐시 안에서는 다시 받지 않는다(공식 호출 1회).
    var filtered =
        mapper.readTree(get("/api/v1/home/patch-notes?category=CLIENT_PATCH").body()).path("data");
    assertThat(filtered.path("total").asLong()).isEqualTo(1);
    verify(source, times(1)).fetchNews();

    // 캐시가 비면 새로 받고, 같은 공지 번호는 제목만 바뀐다. 목록에서 사라진 공지도 남는다.
    redis.delete("home:v1:news");
    get("/api/v1/home/patch-notes");
    var after = awaitTitle("(내용 추가)").path("data");
    assertThat(after.path("total").asLong()).isEqualTo(3);
    assertThat(after.at("/items/2/title").asText()).isEqualTo("10월 7일(수) 업데이트 내역 안내 (내용 추가)");
  }

  @Test
  void 잘못된_조건은_400이다() throws Exception {
    for (var query : List.of("category=OTHER", "page=0", "size=51"))
      assertThat(get("/api/v1/home/patch-notes?" + query).statusCode()).as(query).isEqualTo(400);
    verifyNoInteractions(source);
  }

  private tools.jackson.databind.JsonNode await(String path, long total) throws Exception {
    tools.jackson.databind.JsonNode json = null;
    for (int i = 0; i < 100; i++) {
      json = mapper.readTree(get(path).body());
      // 목록과 개수는 다른 쿼리라, 비동기 저장이 그 사이에 끝나면 total은 찼는데 items는 빈 응답이 올 수 있다. 둘 다 찰 때까지 기다린다.
      if (json.at("/data/total").asLong() >= total && json.at("/data/items").size() >= total)
        return json;
      Thread.sleep(50);
    }
    return json;
  }

  private tools.jackson.databind.JsonNode awaitTitle(String fragment) throws Exception {
    tools.jackson.databind.JsonNode json = null;
    for (int i = 0; i < 100; i++) {
      json = mapper.readTree(get("/api/v1/home/patch-notes").body());
      if (json.toString().contains(fragment)) return json;
      Thread.sleep(50);
    }
    return json;
  }

  private static NewsFeed.Notice notice(String title, LocalDateTime at, int id) {
    return new NewsFeed.Notice(title, NewsFeed.Category.NOTICE, at, BASE + id);
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
