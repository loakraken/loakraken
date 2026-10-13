package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 원정대 캐릭터별 전투력(L19). 설계 문서의 병합 조건 테스트(B5·B6·B7·B8). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RosterCombatPowerIntegrationTest {
  private static final String SERVER = "원정전투서버";
  private static final String OTHER = "원정전투다른서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    for (var table :
        List.of("character_title_observation", "character_observation", "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name IN (:s, :o))")
          .param("s", SERVER)
          .param("o", OTHER)
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :o)")
        .param("s", SERVER)
        .param("o", OTHER)
        .update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '원정전투%'").update();
    var keys = new ArrayList<String>();
    for (var pattern :
        List.of(
            "character:v11:원정전투*",
            "character:v1:원정전투*",
            "roster-cp:v1:refresh:*",
            "lostark:client-limit:*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 조회는_기록만_읽고_처리정지는_기록_없는_캐릭터와_같다() throws Exception {
    roster("원정전투가", "원정전투나", "원정전투다", "원정전투정지");
    record("원정전투가", "3100.50", Instant.now().minus(Duration.ofHours(2)));
    record("원정전투정지", "4000.00", Instant.now());
    // 관측한 최고 전투력(L37과 같은 열): 가는 3300.00, 처리정지 캐릭터도 값은 있지만 응답에는 나오지 않아야 한다.
    best("원정전투가", "3300.00");
    best("원정전투정지", "4500.00");
    stop("원정전투정지");

    var response = get("/api/v1/characters/roster-combat-power?characterName=" + encode("원정전투가"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("roster-combat-power.json", response.body());
    var data = data(response);
    var items = data.path("items");
    // 같은 서버 넷(다른 서버 캐릭터는 빠짐), 공식 원정대 순서
    assertThat(names(items)).containsExactly("원정전투가", "원정전투나", "원정전투다", "원정전투정지");
    assertThat(items.at("/0/combatPower").decimalValue()).isEqualByComparingTo("3100.50");
    assertThat(items.at("/0/combatPowerObservedAt").isNull()).isFalse();
    assertThat(items.at("/0/bestCombatPower").decimalValue()).isEqualByComparingTo("3300.00");
    assertThat(items.at("/1/combatPower").isNull()).isTrue();
    assertThat(items.at("/1/combatPowerObservedAt").isNull()).isTrue();
    assertThat(items.at("/1/bestCombatPower").isNull()).isTrue();
    // 처리정지 캐릭터는 기록이 있어도 기록 없는 캐릭터와 같은 모양(B5)
    assertThat(items.at("/3/combatPower").isNull()).isTrue();
    assertThat(items.at("/3/combatPowerObservedAt").isNull()).isTrue();
    assertThat(items.at("/3/bestCombatPower").isNull()).isTrue();
    assertThat(ImmutableNames.of(items.get(3))).isEqualTo(ImmutableNames.of(items.get(1)));
    assertThat(data.path("missingCount").asInt()).isEqualTo(3);
    assertThat(data.path("refreshAvailableAt").isNull()).isTrue();
    // 원정대 목록은 캐시: 두 번째 조회는 공식 호출 0
    get("/api/v1/characters/roster-combat-power?characterName=" + encode("원정전투가"));
    verify(detailsPort, times(1)).fetchSiblings(anyString());
    verify(detailsPort, never()).fetchProfile(anyString(), anyInt());
  }

  @Test
  void 불러오기는_기록이_없거나_오래된_캐릭터만_부르고_처리정지는_기록하지_않으며_쿨다운은_409다() throws Exception {
    roster("원정전투가", "원정전투나", "원정전투다", "원정전투정지");
    record("원정전투가", "3100.50", Instant.now().minus(Duration.ofHours(2)));
    record("원정전투다", "2900.00", Instant.now().minus(Duration.ofHours(30)));
    stop("원정전투정지");
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenAnswer(invocation -> profile(invocation.getArgument(0), "3500.00"));

    var response = post("원정전투가", "203.0.113.20");
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("roster-combat-power-refresh.json", response.body());
    var data = data(response);
    // 기록 없는 나, 30시간 지난 다 = 2명. 2시간 전 기록인 가는 부르지 않는다. 처리정지는 공식 호출 전에 빠진다(PM-프론트 P1).
    // 사용자 몫 30을 남긴다(B7).
    verify(detailsPort, never()).fetchProfile(eq("원정전투가"), anyInt());
    verify(detailsPort, never()).fetchProfile(eq("원정전투정지"), anyInt());
    verify(detailsPort, times(2)).fetchProfile(anyString(), eq(30));
    assertThat(data.path("fetched").asInt()).isEqualTo(2);
    assertThat(data.path("remaining").asInt()).isZero();
    assertThat(data.path("refreshAvailableAt").isNull()).isFalse();
    var items = data.path("items");
    assertThat(items.at("/1/combatPower").decimalValue()).isEqualByComparingTo("3500.00");
    assertThat(items.at("/2/combatPower").decimalValue()).isEqualByComparingTo("3500.00");
    // 불러온 값은 최고 전투력에도 반영된다(GREATEST). 처리정지 캐릭터는 null.
    assertThat(items.at("/1/bestCombatPower").decimalValue()).isEqualByComparingTo("3500.00");
    assertThat(items.at("/2/bestCombatPower").decimalValue()).isEqualByComparingTo("3500.00");
    assertThat(items.at("/3/bestCombatPower").isNull()).isTrue();
    // 처리정지 캐릭터는 불러와도 기록하지 않는다(B5).
    assertThat(items.at("/3/combatPower").isNull()).isTrue();
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM characters WHERE character_name = '원정전투정지'")
                .query(Long.class)
                .single())
        .isZero();

    var again = post("원정전투가", "203.0.113.21");
    assertThat(again.statusCode()).isEqualTo(429);
    assertThat(again.body()).contains("ROSTER_REFRESH_COOLDOWN");
    assertThat(again.headers().firstValue("Retry-After")).isPresent();
    var view = data(get("/api/v1/characters/roster-combat-power?characterName=" + encode("원정전투가")));
    assertThat(view.path("refreshAvailableAt").isNull()).isFalse();
    assertThat(view.path("missingCount").asInt()).isEqualTo(1);
  }

  @Test
  void 한_번에_12명까지이고_공식_호출_여유가_모자라면_받은_만큼만_준다() throws Exception {
    var names = new ArrayList<String>();
    for (int i = 0; i < 14; i++) names.add("원정전투많" + (char) ('가' + i));
    roster(names.toArray(String[]::new));
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenAnswer(invocation -> profile(invocation.getArgument(0), "3000.00"));
    var data = data(post(names.getFirst(), "203.0.113.30"));
    assertThat(data.path("fetched").asInt()).isEqualTo(12);
    assertThat(data.path("remaining").asInt()).isEqualTo(2);

    // 여유 부족: 세 번째에서 한도 → 두 명만 기록하고 200 + remaining.
    정리();
    roster("원정전투부가", "원정전투부나", "원정전투부다", "원정전투부라");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 3)
                throw new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED);
              return profile(invocation.getArgument(0), "3000.00");
            });
    var partial = post("원정전투부가", "203.0.113.31");
    assertThat(partial.statusCode()).isEqualTo(200);
    assertThat(data(partial).path("fetched").asInt()).isEqualTo(2);
    assertThat(data(partial).path("remaining").asInt()).isEqualTo(2);

    // 한 명도 못 부르면 쿨다운을 풀어 바로 다시 누를 수 있다.
    정리();
    roster("원정전투영가", "원정전투영나");
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED));
    var none = data(post("원정전투영가", "203.0.113.32"));
    assertThat(none.path("fetched").asInt()).isZero();
    assertThat(none.path("refreshAvailableAt").isNull()).isTrue();
    assertThat(post("원정전투영가", "203.0.113.33").statusCode()).isEqualTo(200);
  }

  @Test
  void 처리정지_대원과_없는_캐릭터는_응답_숫자가_같다() throws Exception {
    // B5: 처리정지 대원도 대상·12명 상한 안에 두고 공식 호출 없이 건너뛴다. 없는 캐릭터와 fetched·remaining·빈 칸 수가 같다.
    var stopped = rosterWithFirst("원정전투정", true);
    var missing = rosterWithFirst("원정전투없", false);
    assertThat(stopped.path("fetched").asInt())
        .isEqualTo(missing.path("fetched").asInt())
        .isEqualTo(11);
    assertThat(stopped.path("remaining").asInt())
        .isEqualTo(missing.path("remaining").asInt())
        .isEqualTo(1);
    assertThat(nullCount(stopped.path("items")))
        .isEqualTo(nullCount(missing.path("items")))
        .isEqualTo(2);
    verify(detailsPort, never()).fetchProfile(eq("원정전투정가"), anyInt());
  }

  /** 13명 원정대(첫 대원이 아이템 레벨 가장 높음). stop이면 첫 대원은 처리정지, 아니면 공식 응답에 없는 캐릭터다. */
  private JsonNode rosterWithFirst(String prefix, boolean stop) throws Exception {
    var names = new ArrayList<String>();
    for (int i = 0; i < 13; i++) names.add(prefix + (char) ('가' + i));
    roster(names.toArray(String[]::new));
    if (stop) stop(names.getFirst());
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              if (invocation.getArgument(0).equals(names.getFirst()))
                throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
              return profile(invocation.getArgument(0), "3000.00");
            });
    return data(post(names.get(1), stop ? "203.0.113.60" : "203.0.113.61"));
  }

  private static long nullCount(JsonNode items) {
    long count = 0;
    for (var item : items) if (item.path("combatPower").isNull()) count++;
    return count;
  }

  @Test
  void 같은_원정대_동시_요청은_공식_호출_한_벌로_묶인다() throws Exception {
    roster("원정전투동가", "원정전투동나", "원정전투동다");
    var gate = new CountDownLatch(1);
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              gate.await(5, TimeUnit.SECONDS);
              return profile(invocation.getArgument(0), "3000.00");
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> post("원정전투동가", "203.0.113.40"));
      var second = pool.submit(() -> post("원정전투동나", "203.0.113.41"));
      Thread.sleep(500);
      gate.countDown();
      var a = first.get(10, TimeUnit.SECONDS);
      var b = second.get(10, TimeUnit.SECONDS);
      assertThat(List.of(a.statusCode(), b.statusCode())).containsOnly(200);
    }
    verify(detailsPort, times(3)).fetchProfile(anyString(), anyInt());
  }

  @Test
  void 클라이언트당_분당_2회를_넘으면_429다() throws Exception {
    roster("원정전투한가");
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenAnswer(invocation -> profile(invocation.getArgument(0), "3000.00"));
    assertThat(post("원정전투한가", "198.51.100.50").statusCode()).isEqualTo(200);
    // 두 번째는 원정대 쿨다운(429 ROSTER_REFRESH_COOLDOWN), 세 번째는 클라이언트 분당 2회 한도(429).
    var cooldown = post("원정전투한가", "198.51.100.50");
    assertThat(cooldown.statusCode()).isEqualTo(429);
    assertThat(cooldown.body()).contains("ROSTER_REFRESH_COOLDOWN");
    var limited = post("원정전투한가", "198.51.100.50");
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.body()).doesNotContain("ROSTER_REFRESH_COOLDOWN");
  }

  private void roster(String... names) {
    var siblings = new ArrayList<CharacterSibling>();
    for (int i = 0; i < names.length; i++)
      siblings.add(
          new CharacterSibling(names[i], SERVER, "바드", 70, BigDecimal.valueOf(1690 - 30L * i)));
    siblings.add(new CharacterSibling("원정전투남", OTHER, "바드", 70, new BigDecimal("1700")));
    when(detailsPort.fetchSiblings(anyString())).thenReturn(siblings);
  }

  private void record(String name, String combatPower, Instant at) {
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              combat_power, last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, '바드', 1690, :cp, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", SERVER)
        .param("cp", new BigDecimal(combatPower))
        .param("at", at.atOffset(ZoneOffset.UTC))
        .update();
  }

  private void best(String name, String combatPower) {
    jdbc.sql(
            "UPDATE characters SET best_combat_power = :cp WHERE character_name = :n AND server_name = :s")
        .param("cp", new BigDecimal(combatPower))
        .param("n", name)
        .param("s", SERVER)
        .update();
  }

  private void stop(String name) {
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'PRIVACY_STOP')")
        .param("n", name)
        .param("s", SERVER)
        .update();
  }

  private static CharacterProfile profile(String name, String combatPower) {
    return new CharacterProfile(
        name,
        SERVER,
        "바드",
        70,
        new BigDecimal("1690"),
        new BigDecimal(combatPower),
        null,
        null,
        null,
        null,
        List.of());
  }

  /** 응답 필드 구성(처리정지 표시 필드가 따로 없는지). */
  private static final class ImmutableNames {
    static List<String> of(JsonNode node) {
      var names = new ArrayList<String>();
      node.propertyNames().forEach(names::add);
      return names;
    }
  }

  private static List<String> names(JsonNode items) {
    var names = new ArrayList<String>();
    items.forEach(item -> names.add(item.path("characterName").asText()));
    return names;
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private HttpResponse<String> post(String name, String clientAddress) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:"
                          + port
                          + "/api/v1/characters/roster-combat-power/refresh?characterName="
                          + encode(name)))
              // 테스트 클라이언트(127.0.0.1)는 신뢰 프록시라 이 헤더가 원격 주소가 된다.
              .header("X-Forwarded-For", clientAddress)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
