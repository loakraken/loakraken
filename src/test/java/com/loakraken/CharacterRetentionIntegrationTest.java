package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterCollectionPort;
import com.loakraken.character.application.CharacterRetentionService;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** B9 보유 기간(#141·#142)과 수집기의 공개 제외 반영. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CharacterRetentionIntegrationTest {
  private static final String SERVER = "보존테스트서버";
  @Autowired CharacterRetentionService retention;
  @Autowired CharacterCollectionPort collection;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :server").param("server", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '보존%'").update();
    jdbc.sql("DELETE FROM character_view_daily WHERE character_name LIKE '보존%'").update();
  }

  @Test
  void 마지막_관측이_1년보다_오래된_캐릭터와_기록을_지우고_최근_관측은_남긴다() {
    var now = Instant.now();
    var old = character("보존오래됨", now.minusSeconds(400L * 86400), null, null);
    // 검색은 오래됐지만 수집기가 최근에 갱신했다 → 남는다.
    var refreshed =
        character("보존수집됨", now.minusSeconds(400L * 86400), now.minusSeconds(86400), null);
    // 원정대에서 최근에 보였다 → 남는다.
    var roster = character("보존원정대", now.minusSeconds(400L * 86400), null, now.minusSeconds(3600));
    var recent = character("보존최근", now.minusSeconds(30L * 86400), null, null);
    for (var id : List.of(old, refreshed, roster, recent))
      children(id, now.minusSeconds(400L * 86400));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('보존오래됨', :server, 'USER_REQUEST')")
        .param("server", SERVER)
        .update();

    var result = retention.purge();

    assertThat(result.characters()).isGreaterThanOrEqualTo(1);
    assertThat(exists(old)).isFalse();
    assertThat(exists(refreshed)).isTrue();
    assertThat(exists(roster)).isTrue();
    assertThat(exists(recent)).isTrue();
    for (var table :
        List.of("character_observation", "character_title_observation", "character_card"))
      assertThat(count(table, old)).as(table).isZero();
    assertThat(count("character_observation", recent)).isEqualTo(1);
    // 공개 제외는 해제 요청 때까지 남긴다(B9 ③).
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM public_exclusion WHERE character_name = '보존오래됨'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void 조회수_일별_행은_365일보다_오래되면_지운다() {
    var today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    view("보존조회오래됨", today.minusDays(400));
    view("보존조회경계", today.minusDays(365));
    view("보존조회최근", today.minusDays(1));
    var result = retention.purge();
    assertThat(result.views()).isGreaterThanOrEqualTo(1);
    assertThat(views("보존조회오래됨")).isZero();
    assertThat(views("보존조회경계")).isEqualTo(1);
    assertThat(views("보존조회최근")).isEqualTo(1);
  }

  @Test
  void 수집기는_공개_제외_캐릭터를_고르거나_원정대로_새로_기록하지_않는다() {
    var excluded = character("보존제외", Instant.now(), null, null);
    jdbc.sql(
            "UPDATE characters SET item_level = 9999, profile_checked_at = NULL, roster_scanned_at = NULL WHERE character_id = :id")
        .param("id", excluded)
        .update();
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('보존제외', NULL, 'USER_REQUEST')")
        .update();
    var profile = collection.nextProfileDue(new BigDecimal("9000"), Instant.now());
    assertThat(profile).isEmpty();
    var rosterTarget = collection.nextRosterDue(Instant.now());
    assertThat(rosterTarget.filter(target -> target.characterName().equals("보존제외"))).isEmpty();
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('보존제외새로', :server, 'USER_REQUEST')")
        .param("server", SERVER)
        .update();
    collection.saveRoster(
        List.of(
            new CharacterSibling("보존제외새로", SERVER, "블레이드", 70, new BigDecimal("1700")),
            new CharacterSibling("보존원정대새로", SERVER, "바드", 70, new BigDecimal("1700"))),
        Instant.now());
    assertThat(name("보존제외새로")).isZero();
    assertThat(name("보존원정대새로")).isEqualTo(1);
  }

  private UUID character(String name, Instant synced, Instant refreshed, Instant rosterScanned) {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
                                last_synced_at, profile_refreshed_at, roster_scanned_at)
        VALUES (:id, :name, :server, '블레이드', 1700, :synced, :refreshed, :roster)
        """)
        .param("id", id)
        .param("name", name)
        .param("server", SERVER)
        .param("synced", synced.atOffset(ZoneOffset.UTC))
        .param("refreshed", refreshed == null ? null : refreshed.atOffset(ZoneOffset.UTC))
        .param("roster", rosterScanned == null ? null : rosterScanned.atOffset(ZoneOffset.UTC))
        .update();
    return id;
  }

  private void children(UUID id, Instant at) {
    var time = at.atOffset(ZoneOffset.UTC);
    jdbc.sql(
            """
        INSERT INTO character_observation (observation_id, character_id, observed_at, last_confirmed_at, class_name, item_level)
        VALUES (:oid, :id, :at, :at, '블레이드', 1700)
        """)
        .param("oid", UUID.randomUUID())
        .param("id", id)
        .param("at", time)
        .update();
    jdbc.sql(
            "INSERT INTO character_title_observation (character_id, title, first_observed_at, last_observed_at) VALUES (:id, '칭호', :at, :at)")
        .param("id", id)
        .param("at", time)
        .update();
    jdbc.sql(
            "INSERT INTO character_card (character_id, card_name, grade, awake_count, observed_at) VALUES (:id, '카드', '전설', 5, :at)")
        .param("id", id)
        .param("at", time)
        .update();
  }

  private void view(String name, LocalDate date) {
    jdbc.sql(
            "INSERT INTO character_view_daily (character_name, view_date, view_count) VALUES (:name, :date, 1)")
        .param("name", name)
        .param("date", date)
        .update();
  }

  private long views(String name) {
    return jdbc.sql("SELECT COUNT(*) FROM character_view_daily WHERE character_name = :name")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  private boolean exists(UUID id) {
    return count("characters", id) > 0;
  }

  private long name(String name) {
    return jdbc.sql(
            "SELECT COUNT(*) FROM characters WHERE server_name = :server AND character_name = :name")
        .param("server", SERVER)
        .param("name", name)
        .query(Long.class)
        .single();
  }

  private long count(String table, UUID id) {
    return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE character_id = :id")
        .param("id", id)
        .query(Long.class)
        .single();
  }
}
