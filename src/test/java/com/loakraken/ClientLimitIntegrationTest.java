package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 유입 제한(#108)과 없는 캐릭터 캐시. 테스트 요청은 루프백(신뢰 프록시)에서 X-Forwarded-For로 공개 주소를 넘겨, 운영의 웹 → 백엔드 경로와 같게 한다.
 * 공식 호출은 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "lostark.client-limit.general-per-minute=4",
      "lostark.client-limit.stats-per-minute=2",
      "lostark.client-limit.bot-per-minute=4"
    })
class ClientLimitIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private StringRedisTemplate redis;
  @MockitoBean private CharacterDetailsPort detailsPort;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void 공개_주소별로_분당_요청을_세고_넘으면_429와_Retry_After를_준다() throws Exception {
    String address = "203.0.113." + (10 + (int) (Math.random() * 200));
    // 1분 고정 창이라 창 끝 직전이면 다음 창까지 기다려 같은 창 안에서 센다.
    long second = (System.currentTimeMillis() / 1000) % 60;
    if (second >= 50) Thread.sleep((61 - second) * 1000);
    assertThat(get("/api/v1/stats/summary", address).statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/rankings?metric=COMBAT_POWER", address).statusCode()).isEqualTo(200);
    var stats = get("/api/v1/stats/summary", address);
    assertThat(stats.statusCode()).isEqualTo(429);
    assertThat(stats.body()).contains("\"code\":\"CLIENT_RATE_LIMITED\"");
    assertThat(Long.parseLong(stats.headers().firstValue("Retry-After").orElseThrow()))
        .isBetween(1L, 60L);
    // GENERAL은 4회까지: 위 3회 + 1회
    assertThat(get("/api/v1/markets/tabs", address).statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/markets/tabs", address).statusCode()).isEqualTo(429);
    // 다른 주소와 루프백(헤더 없는 내부 요청)은 영향이 없다.
    assertThat(get("/api/v1/markets/tabs", "198.51.100.1").statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/markets/tabs", null).statusCode()).isEqualTo(200);
    // Valkey 키에 주소 원문을 남기지 않는다.
    var keys = redis.keys("lostark:client-limit:*");
    assertThat(keys).isNotEmpty().noneMatch(key -> key.contains(address));
  }

  @Test
  void 랭킹_아래_경로와_경로_매개변수가_붙은_경로도_센다() throws Exception {
    // STATS 2: 랭킹 아래 경로도 통계 칸이고, 원문 URI에 ';…'가 붙어도 정규화된 경로로 센다.
    long second = (System.currentTimeMillis() / 1000) % 60;
    if (second >= 50) Thread.sleep((61 - second) * 1000);
    String address = "203.0.113." + (10 + (int) (Math.random() * 200));
    var statuses = new java.util.ArrayList<Integer>();
    for (var path :
        new String[] {
          "/api/v1/rankings/guilds", "/api/v1/rankings;x=1/guilds", "/api/v1/rankings/rosters"
        }) statuses.add(get(path, address).statusCode());
    statuses.add(get("/api/v1/rankings/rosters", address).statusCode());
    // ';' 경로가 처리되면(200) 그것도 세어 세 번째부터 429, 컨테이너가 400으로 거절하면 세지 않아 네 번째가 429.
    assertThat(statuses.get(1)).as(statuses.toString()).isIn(200, 400);
    if (statuses.get(1) == 200) assertThat(statuses.get(2)).as(statuses.toString()).isEqualTo(429);
    assertThat(statuses.get(3)).as(statuses.toString()).isEqualTo(429);
    // 접두 앞쪽의 ';…'도 필터를 비켜 가지 않는다(GENERAL 4: 위 3회 + 1회, 다음은 429).
    String other = "198.51.100." + (10 + (int) (Math.random() * 200));
    for (int i = 0; i < 4; i++) get("/api;x=1/v1/markets/tabs", other);
    assertThat(get("/api;x=1/v1/markets/tabs", other).statusCode()).isIn(400, 429);
  }

  @Test
  void 공식_API가_없다고_답한_이름은_5분_동안_공식_호출_없이_404다() throws Exception {
    String name = "없는캐릭" + UUID.randomUUID().toString().substring(0, 6);
    when(detailsPort.fetch(name))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    String path = "/api/v1/characters/search?characterName=" + encode(name);
    var first = get(path, null);
    var second = get(path, null);
    assertThat(first.statusCode()).isEqualTo(404);
    assertThat(second.statusCode()).isEqualTo(404);
    assertThat(second.body()).contains("CHARACTER_NOT_FOUND");
    verify(detailsPort, times(1)).fetch(name);
    String key = "character:v1:" + name + ":missing";
    assertThat(redis.getExpire(key)).isBetween(1L, 300L);
    redis.delete(key);
  }

  @Test
  void 한도_초과와_타임아웃은_없는_캐릭터로_기억하지_않는다() throws Exception {
    String name = "한도캐릭" + UUID.randomUUID().toString().substring(0, 6);
    when(detailsPort.fetch(name))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_API_TIMEOUT))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED));
    String path = "/api/v1/characters/search?characterName=" + encode(name);
    assertThat(get(path, null).statusCode()).isEqualTo(429);
    assertThat(get(path, null).statusCode()).isEqualTo(504);
    assertThat(get(path, null).statusCode()).isEqualTo(429);
    verify(detailsPort, times(3)).fetch(name);
    assertThat(redis.hasKey("character:v1:" + name + ":missing")).isFalse();
  }

  private HttpResponse<String> get(String path, String forwardedFor) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
