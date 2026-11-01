package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterCardPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 연마 효과 줄 기록과 집계 SQL(deploy/sql/polish-tally.sql): 캐릭터마다 마지막 관측만, 서로 다른 캐릭터 수로 센다. 처리정지 목록은 뺀다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PolishLineTallyIntegrationTest {
  private static final String SERVER = "연마집계서버";
  private static final Instant T0 = Instant.parse("2026-10-25T00:00:00Z");
  @Autowired JdbcClient jdbc;
  @Autowired CharacterCardPort cards;

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '연마%'").update();
  }

  private void character(String name) {
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, '바드', 1700, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", SERVER)
        .param("at", OffsetDateTime.now(ZoneOffset.UTC))
        .update();
  }

  private static CharacterCardPort.PolishLine line(String value, String colour) {
    return new CharacterCardPort.PolishLine("목걸이", 1680, "고대", "추가 피해", value, colour);
  }

  private List<Map<String, Object>> tally() throws Exception {
    var sql = Files.readString(Path.of("deploy/sql/polish-tally.sql"), StandardCharsets.UTF_8);
    return jdbc.sql(sql.strip().replaceAll(";$", "")).query().listOfRows().stream()
        .filter(row -> "추가 피해".equals(row.get("option_name")))
        .toList();
  }

  @Test
  void 캐릭터마다_마지막_관측만_서로_다른_캐릭터_수로_센다() throws Exception {
    character("연마가");
    character("연마나");
    character("연마제외");
    cards.recordPolishLines(SERVER, "연마가", List.of(line("+2.60%", "HIGH")), T0);
    // 같은 캐릭터를 다시 관측해도 늘지 않고, 더 늦은 관측의 줄로 바뀐다.
    cards.recordPolishLines(SERVER, "연마가", List.of(line("+1.60%", "MID")), T0.plusSeconds(60));
    // 늦게 도착한 옛 관측은 덮지 않는다.
    cards.recordPolishLines(SERVER, "연마가", List.of(line("+0.60%", "LOW")), T0.plusSeconds(30));
    cards.recordPolishLines(SERVER, "연마나", List.of(line("+1.60%", "MID")), T0);
    cards.recordPolishLines(SERVER, "연마제외", List.of(line("+2.60%", "HIGH")), T0);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('연마제외', NULL, 'USER_REQUEST')")
        .update();

    var rows = tally();
    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(row.get("option_value")).isEqualTo("+1.60%");
    assertThat(row.get("colour")).isEqualTo("MID");
    assertThat(row.get("item_level")).isEqualTo(1680);
    assertThat(((Number) row.get("characters")).longValue()).isEqualTo(2);
  }

  @Test
  void 기록이_없는_캐릭터는_아무것도_하지_않는다() throws Exception {
    cards.recordPolishLines(SERVER, "연마없는캐", List.of(line("+2.60%", "HIGH")), T0);
    assertThat(tally()).isEmpty();
  }
}
