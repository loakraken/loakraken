package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterHistoryPort;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CharacterHistoryIntegrationTest {
  private static final String NAME = "그래프이력";
  private static final Instant TIME = Instant.parse("2026-10-05T00:00:00Z");
  @LocalServerPort private int port;
  @Autowired private CharacterHistoryPort history;
  @Autowired private JdbcClient jdbc;
  @Autowired private JsonMapper mapper;

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql(
            "DELETE FROM character_observation WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)")
        .param("name", NAME)
        .update();
    jdbc.sql("DELETE FROM characters WHERE character_name = :name").param("name", NAME).update();
  }

  @Test
  void 같은_값의_재수집은_새_점을_만들지_않고_마지막_확인_시각만_갱신한다() throws Exception {
    history.record(profile("1700.00", "4000.10", 260), TIME);
    history.record(profile("1700.00", "4000.1", 260), TIME.plusSeconds(600));
    history.record(profile("1710.00", "4100.00", 260), TIME.plusSeconds(1200));
    history.record(profile("1710.00", "4100.00", 261), TIME.plusSeconds(1800));
    var body = mapper.readTree(request("&limit=10").body());
    assertThat(body.path("code").asText()).isEqualTo("CHARACTER_HISTORY_SUCCESS");
    var points = body.at("/data/points");
    assertThat(points.size()).isEqualTo(3);
    assertThat(points.get(0).path("observedAt").asText()).isEqualTo("2026-10-05T00:00:00Z");
    assertThat(points.get(0).path("lastConfirmedAt").asText()).isEqualTo("2026-10-05T00:10:00Z");
    assertThat(points.get(1).path("itemLevel").decimalValue()).isEqualByComparingTo("1710");
    assertThat(points.get(2).path("expeditionLevel").asInt()).isEqualTo(261);
    ApiContractFixture.write("character-history.json", request("&limit=10").body());
  }

  @Test
  void 오래된_수집은_최신_관측을_덮어쓰지_않고_limit은_최근_점만_오름차순으로_준다() throws Exception {
    history.record(profile("1700.00", null, 260), TIME);
    history.record(profile("1720.00", null, 260), TIME.plusSeconds(600));
    history.record(profile("1690.00", null, 260), TIME.minusSeconds(600));
    history.record(profile("1730.00", null, 260), TIME.plusSeconds(1200));
    var points = mapper.readTree(request("&limit=2").body()).at("/data/points");
    assertThat(points.size()).isEqualTo(2);
    assertThat(points.get(0).path("itemLevel").decimalValue()).isEqualByComparingTo("1720");
    assertThat(points.get(1).path("itemLevel").decimalValue()).isEqualByComparingTo("1730");
    assertThat(points.get(1).path("combatPower").isNull()).isTrue();
  }

  @Test
  void 기록이_없으면_빈_목록이고_잘못된_limit은_400이다() throws Exception {
    var empty = request("");
    assertThat(empty.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(empty.body()).at("/data/points").size()).isZero();
    assertThat(request("&limit=501").statusCode()).isEqualTo(400);
    assertThat(send("/api/v1/characters/history?characterName=" + encode(NAME)).statusCode())
        .isEqualTo(400);
  }

  private CharacterProfile profile(String itemLevel, String combatPower, int expeditionLevel) {
    return new CharacterProfile(
        NAME,
        "루페온",
        "바드",
        70,
        new BigDecimal(itemLevel),
        combatPower == null ? null : new BigDecimal(combatPower),
        expeditionLevel,
        null,
        null,
        null,
        List.of());
  }

  private HttpResponse<String> request(String extra) throws Exception {
    return send(
        "/api/v1/characters/history?characterName="
            + encode(NAME)
            + "&serverName="
            + encode("루페온")
            + extra);
  }

  private HttpResponse<String> send(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
