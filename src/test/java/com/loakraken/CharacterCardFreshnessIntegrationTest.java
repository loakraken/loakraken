package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterCardPort;
import com.loakraken.character.domain.CardCollection;
import java.math.BigDecimal;
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

/** 관측 기록 순서(B3 신선도): 늦게 도착한 옛 관측은 포지션·효율을 덮지 않고, 장비가 빈 관측은 효율을 비운다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CharacterCardFreshnessIntegrationTest {
  private static final String SERVER = "신선도테스트서버";
  private static final String NAME = "신선도캐";
  private static final Instant T0 = Instant.parse("2026-10-25T00:00:00Z");
  @Autowired JdbcClient jdbc;
  @Autowired CharacterCardPort cards;

  @BeforeEach
  void 준비() {
    정리();
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, '바드', 1700, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", NAME)
        .param("server", SERVER)
        .param("at", OffsetDateTime.now(ZoneOffset.UTC))
        .update();
  }

  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
  }

  private void record(String position, Instant at, CharacterCardPort.Efficiency efficiency) {
    cards.record(
        new CharacterCardPort.Observation(
            SERVER,
            NAME,
            List.of(),
            CardCollection.learn(null),
            position,
            null,
            false,
            at,
            efficiency));
  }

  private static CharacterCardPort.Efficiency real(String bracelet) {
    return new CharacterCardPort.Efficiency(
        null, null, null, null, new BigDecimal(bracelet), "bracelet-real-v1", null, null);
  }

  private Map<String, Object> row() {
    return jdbc.sql(
            "SELECT position, bracelet_real_pct FROM characters WHERE server_name = :s AND character_name = :n")
        .param("s", SERVER)
        .param("n", NAME)
        .query()
        .singleRow();
  }

  @Test
  void 늦게_도착한_옛_관측은_포지션과_효율을_덮지_않는다() {
    record("SUPPORT", T0.plusSeconds(60), real("13.47"));
    // 더 이른 관측(딜러 시절)이 나중에 도착해도 서포터 값이 딜러 랭킹으로 가지 않게 그대로 둔다.
    record("DEALER", T0, real("20.00"));
    assertThat(row().get("position")).isEqualTo("SUPPORT");
    assertThat((BigDecimal) row().get("bracelet_real_pct")).isEqualByComparingTo("13.47");

    record("DEALER", T0.plusSeconds(120), real("11.30"));
    assertThat(row().get("position")).isEqualTo("DEALER");
    assertThat((BigDecimal) row().get("bracelet_real_pct")).isEqualByComparingTo("11.30");
  }

  @Test
  void 장비가_빈_관측은_저장된_효율을_비운다() {
    record("DEALER", T0, real("11.30"));
    record(
        "DEALER",
        T0.plusSeconds(60),
        new CharacterCardPort.Efficiency(null, null, null, null, null, null, null, null));
    assertThat(row().get("bracelet_real_pct")).isNull();
  }
}
