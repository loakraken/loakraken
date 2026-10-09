package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.TestcontainersConfiguration;
import com.loakraken.character.application.EquipmentSummaryPort.Outcome;
import com.loakraken.character.domain.EquipmentSummary;
import com.loakraken.character.domain.EquipmentSummaryTestData;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 장비 요약 구간 행(#146): 같은 관측은 행 1개, 바뀌면 새 행, 캐릭터 삭제 시 함께 삭제. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EquipmentSummaryRepositoryIntegrationTest {
  private static final String SERVER = "요약테스트서버";
  private static final String NAME = "요약테스트캐릭터";
  private static final Instant AT = Instant.parse("2026-10-09T07:00:00Z");
  @Autowired PostgresEquipmentSummaryRepository repository;
  @Autowired JdbcClient jdbc;
  private UUID characterId;

  @BeforeEach
  void 캐릭터_준비() {
    cleanUp();
    characterId = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO characters (character_id, server_name, character_name, class_name, item_level, last_synced_at)
        VALUES (:id, :server, :name, '블레이드', 1770, :at)
        """)
        .param("id", characterId)
        .param("server", SERVER)
        .param("name", NAME)
        .param("at", AT.atOffset(java.time.ZoneOffset.UTC))
        .update();
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :server").param("server", SERVER).update();
  }

  @Test
  void 같은_장비를_두_번_관측하면_행_1개이고_마지막_확인_시각만_바뀐다() {
    var summary = EquipmentSummaryTestData.summary("4035.27", false);
    assertThat(repository.record(SERVER, NAME, summary, AT)).isEqualTo(Outcome.CREATED);
    // 공식 순서만 바뀐 같은 장비
    var reordered = EquipmentSummaryTestData.summary("4035.27", true);
    assertThat(repository.record(SERVER, NAME, reordered, AT.plusSeconds(600)))
        .isEqualTo(Outcome.CONFIRMED);
    assertThat(rows()).isEqualTo(1);
    var confirmed =
        jdbc.sql(
                "SELECT last_confirmed_at FROM character_equipment_summary WHERE character_id = :id")
            .param("id", characterId)
            .query(java.time.OffsetDateTime.class)
            .single();
    assertThat(confirmed.toInstant()).isEqualTo(AT.plusSeconds(600));
    var stored =
        jdbc.sql(
                """
            SELECT schema_version, features->'weapon'->>'weaponAttack', combat_power, class_name
            FROM character_equipment_summary WHERE character_id = :id
            """)
            .param("id", characterId)
            .query(
                (row, index) ->
                    row.getString(1)
                        + "|"
                        + row.getString(2)
                        + "|"
                        + row.getBigDecimal(3).toPlainString()
                        + "|"
                        + row.getString(4))
            .single();
    assertThat(stored).isEqualTo("equipment-summary-v1|208130|4035.27|블레이드");
  }

  @Test
  void 공식_전투력이나_특성이_바뀌면_새_행을_만든다() {
    repository.record(SERVER, NAME, EquipmentSummaryTestData.summary("4035.27", false), AT);
    assertThat(
            repository.record(
                SERVER,
                NAME,
                EquipmentSummaryTestData.summary("4036.00", false),
                AT.plusSeconds(60)))
        .isEqualTo(Outcome.CREATED);
    assertThat(rows()).isEqualTo(2);
    // 이전 값으로 돌아가도 최신 행과 다르므로 새 행이다.
    assertThat(
            repository.record(
                SERVER,
                NAME,
                EquipmentSummaryTestData.summary("4035.27", false),
                AT.plusSeconds(120)))
        .isEqualTo(Outcome.CREATED);
    assertThat(rows()).isEqualTo(3);
  }

  @Test
  void 캐릭터_행이_없으면_건너뛰고_캐릭터를_지우면_함께_지운다() {
    assertThat(repository.record(SERVER, "없는캐릭터", EquipmentSummaryTestData.summary("1", false), AT))
        .isEqualTo(Outcome.SKIPPED);
    repository.record(SERVER, NAME, EquipmentSummaryTestData.summary("4035.27", false), AT);
    jdbc.sql("DELETE FROM characters WHERE character_id = :id").param("id", characterId).update();
    assertThat(rows()).isZero();
  }

  @Test
  void 해시는_키_정렬_직렬화와_전투력으로_만든다() {
    var a = EquipmentSummaryTestData.summary("4035.27", false);
    var b = EquipmentSummaryTestData.summary("4035.270", true);
    var featuresA = PostgresEquipmentSummaryRepository.CANONICAL.writeValueAsString(a.features());
    var featuresB = PostgresEquipmentSummaryRepository.CANONICAL.writeValueAsString(b.features());
    assertThat(featuresA).isEqualTo(featuresB);
    assertThat(PostgresEquipmentSummaryRepository.hash(featuresA, a))
        .isEqualTo(PostgresEquipmentSummaryRepository.hash(featuresB, b))
        .hasSize(64);
    assertThat(EquipmentSummary.SCHEMA_VERSION).isEqualTo("equipment-summary-v1");
  }

  private long rows() {
    return jdbc.sql("SELECT COUNT(*) FROM character_equipment_summary WHERE character_id = :id")
        .param("id", characterId)
        .query(Long.class)
        .single();
  }
}
