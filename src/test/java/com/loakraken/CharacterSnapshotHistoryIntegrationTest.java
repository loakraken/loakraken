package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.loakraken.character.application.CharacterCache;
import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterSnapshotCommandService;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "character.snapshots.enabled=true")
class CharacterSnapshotHistoryIntegrationTest {
  private static final String NAME = "이력테스트";
  private static final Instant TIME = Instant.parse("2026-10-04T00:00:00Z");
  @LocalServerPort private int port;
  @Autowired private CharacterSnapshotCommandService snapshots;
  @Autowired private JdbcClient jdbc;
  @Autowired private JsonMapper mapper;
  @MockitoBean private CharacterDetailsPort details;
  @MockitoBean private CharacterCache cache;

  @BeforeEach
  void 준비() {
    cleanup();
  }

  @AfterEach
  void 정리와_외부_무호출_검증() {
    verifyNoInteractions(details, cache);
    cleanup();
  }

  @Test
  void 저장_기록이_없으면_외부_검색_없이_빈_목록을_반환한다() throws Exception {
    var response = request("루페온", "");
    assertThat(response.statusCode()).isEqualTo(200);
    var body = mapper.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo("CHARACTER_HISTORY_SUCCESS");
    assertThat(body.at("/data/items").size()).isZero();
    assertThat(body.at("/data/nextCursor").isNull()).isTrue();
    ApiContractFixture.write("snapshot-history-empty.json", response.body());
  }

  @Test
  void 동시각_기록과_조회_중_추가된_기록에서도_다음_페이지가_중복되지_않는다() throws Exception {
    save("루페온", "1,700.00", "8,000.10", TIME);
    save("루페온", "1,710.00", "9,000.12", TIME);
    save("루페온", "1,720.50", null, TIME.plusSeconds(60));
    var response = request("루페온", "&limit=2");
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("snapshot-history.json", response.body());
    var first = mapper.readTree(response.body());
    assertThat(first.at("/data/items").size()).isEqualTo(2);
    assertThat(first.at("/data/items/0/itemLevel").decimalValue()).isEqualByComparingTo("1720.50");
    assertThat(first.at("/data/items/0/combatPower").isNull()).isTrue();
    assertThat(first.at("/data/items/1/itemLevel").decimalValue()).isEqualByComparingTo("1710");
    assertThat(first.at("/data/items/1/combatPower").decimalValue())
        .isEqualByComparingTo("9000.12");
    String cursor = first.at("/data/nextCursor").asText();
    assertThat(cursor).isEqualTo(first.at("/data/items/1/snapshotId").asText());
    save("루페온", "1730", "9500", TIME.plusSeconds(120));
    var next = body("루페온", "&limit=2&cursor=" + cursor);
    assertThat(next.at("/data/items").size()).isEqualTo(1);
    assertThat(next.at("/data/items/0/itemLevel").decimalValue()).isEqualByComparingTo("1700");
    assertThat(next.at("/data/nextCursor").isNull()).isTrue();
    assertThat(response.body())
        .doesNotContain("원본툴팁", "armory", "siblings", "Stats", "schemaVersion");
    var latest = body("루페온", "&limit=1");
    assertThat(latest.at("/data/items/0/itemLevel").decimalValue()).isEqualByComparingTo("1730");
  }

  @Test
  void 동일_원본의_재조회는_이력을_늘리거나_과거_시각을_바꾸지_않는다() throws Exception {
    save("루페온", "1700", null, TIME);
    save("루페온", "1700", null, TIME.plusSeconds(60));
    var body = body("루페온", "&limit=1");
    assertThat(body.at("/data/items").size()).isEqualTo(1);
    assertThat(body.at("/data/items/0/fetchedAt").asText()).isEqualTo(TIME.toString());
    assertThat(body.at("/data/nextCursor").isNull()).isTrue();
  }

  @Test
  void 서버가_다르면_기록과_커서를_공유하지_않는다() throws Exception {
    save("루페온", "1700", null, TIME);
    save("아만", "1800", null, TIME);
    var luterra = body("루페온", "");
    var aman = body("아만", "");
    assertThat(luterra.at("/data/items").size()).isEqualTo(1);
    assertThat(luterra.at("/data/items/0/itemLevel").decimalValue()).isEqualByComparingTo("1700");
    assertThat(aman.at("/data/items/0/itemLevel").decimalValue()).isEqualByComparingTo("1800");
    var wrong = request("루페온", "&cursor=" + aman.at("/data/items/0/snapshotId").asText());
    assertThat(wrong.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(wrong.body()).path("code").asText())
        .isEqualTo("INVALID_SNAPSHOT_CURSOR");
    ApiContractFixture.write("snapshot-history-invalid-cursor.json", wrong.body());
    assertThat(request("루페온", "&cursor=" + UUID.randomUUID()).statusCode()).isEqualTo(400);
  }

  @Test
  void 기본_20개와_최대_50개_제한을_적용한다() throws Exception {
    for (int index = 0; index < 51; index++) {
      save("루페온", Integer.toString(1700 + index), null, TIME.plusSeconds(index));
    }
    assertThat(body("루페온", "").at("/data/items").size()).isEqualTo(20);
    var first = body("루페온", "&limit=50");
    assertThat(first.at("/data/items").size()).isEqualTo(50);
    assertThat(
            body("루페온", "&limit=50&cursor=" + first.at("/data/nextCursor").asText())
                .at("/data/items")
                .size())
        .isEqualTo(1);
  }

  @Test
  void 잘못된_입력을_400으로_거부한다() throws Exception {
    for (String suffix :
        List.of("&limit=0", "&limit=51", "&limit=-1", "&limit=abc", "&cursor=invalid")) {
      assertThat(request("루페온", suffix).statusCode()).isEqualTo(400);
    }
    assertThat(request("", "").statusCode()).isEqualTo(400);
    assertThat(request("../", "").statusCode()).isEqualTo(400);
    assertThat(request("가".repeat(65), "").statusCode()).isEqualTo(400);
    assertThat(send("/api/v1/characters/snapshots", "application/json").statusCode())
        .isEqualTo(400);
    assertThat(
            send("/api/v1/characters/snapshots?serverName=" + encode("루페온"), "application/json")
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void 손상된_저장_기록을_빈_이력이나_외부_API_오류로_숨기지_않는다() throws Exception {
    save("루페온", "1700", null, TIME);
    jdbc.sql(
            """
        UPDATE character_snapshot SET armory_json = '{"ArmoryProfile": {"ItemAvgLevel": "invalid"}}'::jsonb
        WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)
        """)
        .param("name", NAME)
        .update();
    var response = request("루페온", "");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("CHARACTER_HISTORY_UNAVAILABLE");
    assertThat(mapper.readTree(response.body()).path("data").isNull()).isTrue();
    assertThat(response.body()).doesNotContain("ItemAvgLevel", "invalid", "SELECT");
    ApiContractFixture.write("snapshot-history-unavailable.json", response.body());
  }

  @Test
  void 지원하지_않는_저장_버전은_503으로_알린다() throws Exception {
    save("루페온", "1700", null, TIME);
    jdbc.sql(
            """
        UPDATE character_snapshot SET schema_version = 2
        WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)
        """)
        .param("name", NAME)
        .update();
    assertThat(request("루페온", "").statusCode()).isEqualTo(503);
  }

  @Test
  void JSON_이외의_응답_요청은_406이다() throws Exception {
    var response =
        send(
            "/api/v1/characters/snapshots?characterName="
                + encode(NAME)
                + "&serverName="
                + encode("루페온"),
            "application/xml");
    assertThat(response.statusCode()).isEqualTo(406);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("NOT_ACCEPTABLE");
  }

  private void cleanup() {
    jdbc.sql(
            "DELETE FROM character_observation WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)")
        .param("name", NAME)
        .update();
    jdbc.sql(
            "DELETE FROM character_snapshot WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)")
        .param("name", NAME)
        .update();
    jdbc.sql("DELETE FROM characters WHERE character_name = :name").param("name", NAME).update();
  }

  private void save(String server, String itemLevel, String power, Instant at) {
    var profile =
        new CharacterProfile(
            NAME,
            server,
            "바드",
            70,
            new BigDecimal(itemLevel.replace(",", "")),
            power == null ? null : new BigDecimal(power.replace(",", "")),
            null,
            null,
            null,
            null,
            List.of());
    String raw =
        """
        {"ArmoryProfile":{"CharacterName":"%s","ServerName":"%s","CharacterClassName":"바드",
        "ItemAvgLevel":"%s","CombatPower":%s},"Tooltip":"원본툴팁"}
        """
            .formatted(NAME, server, itemLevel, mapper.writeValueAsString(power));
    snapshots.save(
        new FetchedCharacter(
            com.loakraken.CharacterTestFixture.details(
                profile, new CharacterLoadout(List.of(), null, null, null, null, null), List.of()),
            raw,
            "[]"),
        at);
  }

  private JsonNode body(String server, String suffix) throws Exception {
    var response = request(server, suffix);
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private HttpResponse<String> request(String server, String suffix) throws Exception {
    return send(
        "/api/v1/characters/snapshots?characterName="
            + encode(NAME)
            + "&serverName="
            + encode(server)
            + suffix,
        "application/json");
  }

  private HttpResponse<String> send(String path, String accept) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .header("Accept", accept)
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
