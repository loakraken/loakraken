package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.character.application.CharacterSnapshotCommandService;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "character.snapshots.enabled=true")
class CharacterSnapshotIntegrationTest {
  private static final String NAME = "스냅샷테스트";
  private static final Instant TIME = Instant.parse("2026-10-04T00:00:00Z");
  @Autowired private CharacterSnapshotCommandService service;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql(
            "DELETE FROM character_title_observation WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)")
        .param("name", NAME)
        .update();
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

  @Test
  void 마이그레이션된_JSONB에_미해석_원본과_정규화_속성을_저장한다() {
    service.save(
        fetched("루페온", "1700", "{\"Tooltip\":\"원본\",\"FutureField\":{\"value\":123}}"), TIME);
    assertThat(count()).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT s.armory_json ->> 'Tooltip' FROM character_snapshot s JOIN characters c USING (character_id) WHERE c.character_name = :name")
                .param("name", NAME)
                .query(String.class)
                .single())
        .isEqualTo("원본");
    assertThat(
            jdbc.sql(
                    "SELECT s.armory_json #>> '{FutureField,value}' FROM character_snapshot s JOIN characters c USING (character_id) WHERE c.character_name = :name")
                .param("name", NAME)
                .query(String.class)
                .single())
        .isEqualTo("123");
    assertThat(
            jdbc.sql("SELECT item_level FROM characters WHERE character_name = :name")
                .param("name", NAME)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("1700");
    assertThat(lastSynced()).isEqualTo(TIME);
  }

  @Test
  void JSON_키_순서가_달라도_동일_결과는_중복_저장하지_않는다() {
    service.save(fetched("루페온", "1700", "{\"a\":1,\"b\":2}"), TIME);
    service.save(fetched("루페온", "1700", "{\"b\":2,\"a\":1}"), TIME.plusSeconds(60));
    assertThat(count()).isEqualTo(1);
    assertThat(lastSynced()).isEqualTo(TIME.plusSeconds(60));
  }

  @Test
  void 변경과_이전_상태로의_복귀는_각각_이력으로_보존한다() {
    service.save(fetched("루페온", "1700", "{\"level\":1700}"), TIME);
    service.save(fetched("루페온", "1710", "{\"level\":1710}"), TIME.plusSeconds(60));
    service.save(fetched("루페온", "1700", "{\"level\":1700}"), TIME.plusSeconds(120));
    assertThat(count()).isEqualTo(3);
  }

  @Test
  void 늦게_도착한_과거_수집은_최신_정보를_덮어쓰지_않는다() {
    service.save(fetched("루페온", "1710", "{\"level\":1710}"), TIME.plusSeconds(60));
    service.save(fetched("루페온", "1700", "{\"level\":1700}"), TIME);
    assertThat(count()).isEqualTo(1);
    assertThat(lastSynced()).isEqualTo(TIME.plusSeconds(60));
    assertThat(
            jdbc.sql("SELECT item_level FROM characters WHERE character_name = :name")
                .param("name", NAME)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("1710");
  }

  @Test
  void 스냅샷_저장_실패는_캐릭터_생성과_수정도_롤백한다() {
    assertThatThrownBy(() -> service.save(fetched("루페온", "1700", "[]"), TIME))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.sql("SELECT count(*) FROM characters WHERE character_name = :name")
                .param("name", NAME)
                .query(Integer.class)
                .single())
        .isZero();
    service.save(fetched("루페온", "1700", "{}"), TIME);
    assertThatThrownBy(() -> service.save(fetched("루페온", "1710", "[]"), TIME.plusSeconds(60)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(lastSynced()).isEqualTo(TIME);
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void 같은_캐릭터의_동시_저장은_행_잠금으로_중복을_방지한다() throws Exception {
    var ready = new CountDownLatch(12);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<?>>();
      try {
        for (int i = 0; i < 12; i++)
          tasks.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("대기 초과");
                    service.save(fetched("루페온", "1700", "{}"), TIME);
                    return null;
                  }));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        start.countDown();
      }
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    }
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void 서버가_다르면_동일인으로_합치지_않는다() {
    service.save(fetched("루페온", "1700", "{}"), TIME);
    service.save(fetched("카단", "1700", "{}"), TIME);
    assertThat(
            jdbc.sql("SELECT count(*) FROM characters WHERE character_name = :name")
                .param("name", NAME)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThat(count()).isEqualTo(2);
  }

  private int count() {
    return jdbc.sql(
            "SELECT count(*) FROM character_snapshot s JOIN characters c USING (character_id) WHERE c.character_name = :name")
        .param("name", NAME)
        .query(Integer.class)
        .single();
  }

  private Instant lastSynced() {
    return jdbc.sql("SELECT last_synced_at FROM characters WHERE character_name = :name")
        .param("name", NAME)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private FetchedCharacter fetched(String server, String itemLevel, String raw) {
    var profile =
        new CharacterProfile(
            NAME,
            server,
            "바드",
            70,
            new BigDecimal(itemLevel),
            null,
            300,
            null,
            null,
            null,
            List.of());
    var details =
        com.loakraken.CharacterTestFixture.details(
            profile, new CharacterLoadout(List.of(), null, null, null, null, null), List.of());
    return new FetchedCharacter(details, raw, "[]");
  }
}
