package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.stats.application.PopulationSnapshotPort;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전체 인구 탭(통계 묶음 B5): 조건 없이 저장 캐릭터를 서버·직업·레벨(10 단위)로 센 주간 스냅샷. 처리정지 목록은 빠지고, 전투력이 없는 1640 미만 캐릭터도
 * 들어간다. 오래된 스냅샷은 지운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatsPopulationIntegrationTest {
  private static final String A = "인구테스트가";
  private static final String B = "인구테스트나";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired PopulationSnapshotPort snapshots;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM stats_population_snapshot").update();
    jdbc.sql("DELETE FROM stats_population_snapshot_meta").update();
    jdbc.sql("DELETE FROM public_exclusion WHERE server_name IN (:a, :b)")
        .param("a", A)
        .param("b", B)
        .update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:a, :b)")
        .param("a", A)
        .param("b", B)
        .update();
  }

  @Test
  void 스냅샷이_없으면_날짜_null이고_만들면_서버_직업_레벨별_수를_준다() throws Exception {
    var empty = data("/api/v1/stats/population");
    assertThat(empty.path("snapshotDate").isNull()).isTrue();
    assertThat(empty.path("total").asLong()).isZero();
    assertThat(empty.path("levels").size()).isZero();

    // A: 버서커 1700대 3명(1명은 처리정지) + 바드 1500(전투력 없음, 원정대 목록에서만 저장), B: 바드 1712 2명
    insert(A, "인구버서커1", "버서커", "1700.5", 3000L);
    insert(A, "인구버서커2", "버서커", "1705", 3100L);
    insert(A, "인구버서커3", "버서커", "1708", 3200L);
    insert(A, "인구바드저", "바드", "1500", null);
    insert(B, "인구바드1", "바드", "1712", 3300L);
    insert(B, "인구바드2", "바드", "1719.99", 3400L);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code)"
                + " VALUES ('인구버서커3', :s, 'PRIVACY_STOP')")
        .param("s", A)
        .update();
    // 오래된 스냅샷은 새로 만들 때 지운다.
    snapshots.build(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 1, 1));
    snapshots.build(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 8, 8));
    assertThat(snapshots.latest().map(PopulationSnapshotPort.Latest::date))
        .contains(LocalDate.of(2026, 10, 31));
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM stats_population_snapshot WHERE snapshot_date < '2026-08-08'")
                .query(Long.class)
                .single())
        .isZero();

    var all = data("/api/v1/stats/population");
    assertThat(all.path("snapshotDate").asText()).isEqualTo("2026-10-31");
    assertThat(all.path("server").isNull()).isTrue();
    assertThat(count(all.path("servers"), A)).isEqualTo(3);
    assertThat(count(all.path("servers"), B)).isEqualTo(2);

    var a = data("/api/v1/stats/population?server=" + URLEncoder.encode(A, StandardCharsets.UTF_8));
    assertThat(a.path("server").asText()).isEqualTo(A);
    assertThat(a.path("total").asLong()).isEqualTo(3);
    assertThat(count(a.path("classes"), "버서커")).isEqualTo(2); // 처리정지 1명 빠짐
    assertThat(count(a.path("classes"), "바드")).isEqualTo(1);
    var levels = a.path("levels");
    assertThat(levels.size()).isEqualTo(2);
    assertThat(levels.get(0).path("from").asInt()).isEqualTo(1500);
    assertThat(levels.get(0).path("to").asInt()).isEqualTo(1510);
    assertThat(levels.get(0).path("count").asLong()).isEqualTo(1);
    assertThat(levels.get(1).path("from").asInt()).isEqualTo(1700);
    assertThat(levels.get(1).path("count").asLong()).isEqualTo(2);
    // 서버 목록은 서버를 골라도 전체
    assertThat(count(a.path("servers"), B)).isEqualTo(2);

    var b = data("/api/v1/stats/population?server=" + URLEncoder.encode(B, StandardCharsets.UTF_8));
    assertThat(b.path("total").asLong()).isEqualTo(2);
    assertThat(b.path("levels").get(0).path("from").asInt()).isEqualTo(1710);
    assertThat(b.path("levels").get(0).path("count").asLong()).isEqualTo(2);
    ApiContractFixture.write(
        "stats-population.json",
        get("/api/v1/stats/population?server=" + URLEncoder.encode(A, StandardCharsets.UTF_8))
            .body());
  }

  /**
   * 처리정지는 주간 스냅샷을 기다리지 않는다(코드 리뷰 10-31, #774와 같은 원칙): 스냅샷 뒤에 목록에 넣으면 다음 요청이 지금 목록으로 오늘(KST) 날짜에 다시
   * 만들어 그 캐릭터를 바로 뺀다. 목록이 그대로면 다시 만들지 않는다.
   */
  @Test
  void 스냅샷_뒤에_처리정지에_넣으면_다음_요청부터_바로_빠진다() throws Exception {
    insert(A, "인구즉시1", "버서커", "1700", 3000L);
    insert(A, "인구즉시2", "버서커", "1701", 3000L);
    snapshots.build(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 8, 1));
    var path = "/api/v1/stats/population?server=" + URLEncoder.encode(A, StandardCharsets.UTF_8);
    var before = data(path);
    assertThat(before.path("total").asLong()).isEqualTo(2);
    assertThat(before.path("snapshotDate").asText()).isEqualTo("2026-10-01");
    // 목록이 그대로면 같은 스냅샷(다시 만들지 않음)
    assertThat(data(path).path("snapshotDate").asText()).isEqualTo("2026-10-01");

    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code)"
                + " VALUES ('인구즉시2', :s, 'PRIVACY_STOP')")
        .param("s", A)
        .update();
    var after = data(path);
    assertThat(after.path("total").asLong()).isEqualTo(1);
    assertThat(count(after.path("classes"), "버서커")).isEqualTo(1);
    assertThat(after.path("snapshotDate").asText())
        .isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString());
  }

  private static long count(JsonNode list, String name) {
    for (var item : list)
      if (name.equals(item.path("name").asText())) return item.path("count").asLong();
    return 0;
  }

  private void insert(String server, String name, String className, String itemLevel, Long cp) {
    var at = OffsetDateTime.ofInstant(Instant.now().minus(1, ChronoUnit.DAYS), ZoneOffset.UTC);
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              combat_power, last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, :className, :itemLevel, :cp, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", server)
        .param("className", className)
        .param("itemLevel", new BigDecimal(itemLevel))
        .param("cp", cp == null ? null : new BigDecimal(cp))
        .param("at", at)
        .update();
  }

  private JsonNode data(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
