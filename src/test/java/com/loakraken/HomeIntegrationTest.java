package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.loakraken.home.application.HomeSourcePort;
import com.loakraken.home.domain.GameCalendar;
import com.loakraken.home.domain.NewsFeed;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HomeIntegrationTest {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  @LocalServerPort int port;
  @MockitoBean HomeSourcePort source;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void clearCache() {
    redis.delete(List.of("home:v3:calendar", "home:v1:news"));
  }

  @Test
  void 요청_날짜의_모험_섬_대표_보상과_골드_섬_기타_일정을_주고_캐시를_재사용한다() throws Exception {
    var today = LocalDate.now(KST);
    var later = today.plusDays(1);
    when(source.fetchCalendar())
        .thenReturn(
            new GameCalendar(
                List.of(
                    new GameCalendar.Entry(
                        "모험 섬",
                        "라일라이 아일랜드",
                        "https://cdn-lostark.game.onstove.com/i.png",
                        635,
                        List.of(at(today, 23), at(later, 11)),
                        List.of(
                            new GameCalendar.Reward("실링", null, "일반", List.of()),
                            new GameCalendar.Reward("골드", null, "일반", List.of(at(later, 11))),
                            new GameCalendar.Reward("해적 주화", null, "일반", List.of(at(today, 23))))),
                    new GameCalendar.Entry(
                        "필드보스", "세베크 아툰", null, 1460, List.of(at(later, 11)), List.of()),
                    new GameCalendar.Entry(
                        "카오스게이트", "애니츠", null, 1580, List.of(at(today, 23)), List.of()),
                    new GameCalendar.Entry(
                        "항해", "유령선", null, 1, List.of(at(today, 23)), List.of()))));

    var response = get("/api/v1/home/schedule?date=" + today);
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("HOME_SCHEDULE_SUCCESS");
    assertThat(json.at("/data/ruleVersion").asText()).isEqualTo("home-schedule-v1");
    assertThat(json.at("/data/coveredFrom").asText()).isEqualTo(today.toString());
    // 오늘은 공통 실링과 오늘 23시 해적 주화만 나오고, 다른 날 회차의 골드는 빠진다(F-9).
    assertThat(json.at("/data/adventureIslands/0/rewardGroups/0/rewards").toString())
        .contains("\"name\":\"실링\"", "\"timed\":false", "\"name\":\"해적 주화\"", "\"timed\":true")
        .doesNotContain("골드");
    assertThat(json.at("/data/fieldBosses/0/rewardGroups").isArray()).isTrue();
    assertThat(json.at("/data/adventureIslands/0/rewardKinds").toString())
        .isEqualTo("[\"PIRATE_COIN\"]");
    assertThat(json.at("/data/adventureIslands/0/startTimes/0").asText())
        .endsWith("T23:00:00+09:00");
    assertThat(json.at("/data/fieldBosses/0/startTimes").size()).isZero();
    assertThat(json.at("/data/fieldBosses/0/nextAt").asText()).startsWith(later.toString());
    assertThat(json.at("/data/chaosGates/0/name").asText()).isEqualTo("애니츠");
    assertThat(json.at("/data/others/0/category").asText()).isEqualTo("항해");
    assertThat(json.at("/data/goldIslandDays/0/date").asText()).isEqualTo(later.toString());
    assertThat(json.at("/data/goldIslandDays/0/islands/0").asText()).isEqualTo("라일라이 아일랜드");
    assertThat(json.at("/data/weekly").size()).isEqualTo(2);

    var next = mapper.readTree(get("/api/v1/home/schedule?date=" + later).body());
    assertThat(next.at("/data/adventureIslands/0/rewardKinds").toString()).isEqualTo("[\"GOLD\"]");
    assertThat(next.at("/data/others").size()).isZero();
    verify(source, times(1)).fetchCalendar();
    ApiContractFixture.write("home-schedule.json", response.body());
  }

  @Test
  void 소식은_분류_필터와_최신순_진행_중_이벤트_종료_임박순이다() throws Exception {
    var now = LocalDateTime.now(KST);
    when(source.fetchNews())
        .thenReturn(
            new NewsFeed(
                List.of(
                    new NewsFeed.Notice(
                        "업데이트 안내",
                        NewsFeed.Category.NOTICE,
                        now.minusDays(2),
                        "https://lostark.game.onstove.com/a"),
                    new NewsFeed.Notice(
                        now.getMonthValue() + "월 " + now.getDayOfMonth() + "일(목) 임시 점검 안내",
                        NewsFeed.Category.MAINTENANCE,
                        now.minusHours(1),
                        "https://lostark.game.onstove.com/b"),
                    new NewsFeed.Notice("상점 안내", NewsFeed.Category.SHOP, now.minusDays(1), null)),
                List.of(
                    new NewsFeed.Event(
                        "끝난 이벤트", null, now.minusDays(9), now.minusDays(1), null, null),
                    new NewsFeed.Event(
                        "긴 이벤트", null, now.minusDays(3), now.plusDays(9), null, null),
                    new NewsFeed.Event(
                        "곧 끝남", null, now.minusDays(3), now.plusDays(1), now.plusDays(8), null),
                    new NewsFeed.Event("예정", null, now.plusDays(2), now.plusDays(5), null, null))));

    var response = get("/api/v1/home/news?limit=2");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("HOME_NEWS_SUCCESS");
    assertThat(json.at("/data/notices").size()).isEqualTo(2);
    assertThat(json.at("/data/notices/0/category").asText()).isEqualTo("MAINTENANCE");
    assertThat(json.at("/data/notices/0/maintenanceDate").asText())
        .isEqualTo(now.toLocalDate().toString());
    assertThat(json.at("/data/notices/0/maintenanceDateText").asText()).endsWith("일(목)");
    assertThat(json.at("/data/notices/1/maintenanceDate").isNull()).isTrue();
    assertThat(json.at("/data/notices/1/url").isNull()).isTrue();
    assertThat(json.at("/data/events").size()).isEqualTo(2);
    assertThat(json.at("/data/events/0/title").asText()).isEqualTo("곧 끝남");

    var shop = mapper.readTree(get("/api/v1/home/news?category=SHOP").body());
    assertThat(shop.at("/data/notices").size()).isEqualTo(1);
    verify(source, times(1)).fetchNews();
    ApiContractFixture.write("home-news.json", response.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/home/schedule?date=2026-13-01",
        "/api/v1/home/schedule?date=today",
        "/api/v1/home/news?limit=0",
        "/api/v1/home/news?limit=51",
        "/api/v1/home/news?category=공지"
      })
  void 잘못된_조건은_조회_전에_400이다(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(source);
  }

  private static LocalDateTime at(LocalDate date, int hour) {
    return date.atTime(hour, 0);
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + port + path.replace("공지", "%EA%B3%B5%EC%A7%80")))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
