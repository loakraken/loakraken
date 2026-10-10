package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterHistoryPort;
import com.loakraken.character.application.CharacterObservationPoint;
import com.loakraken.character.application.CharacterTitleObservation;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresCharacterHistoryRepository implements CharacterHistoryPort {
  private final JdbcClient jdbc;

  PostgresCharacterHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 3)
  public void record(CharacterProfile profile, Instant fetchedAt) {
    var at = fetchedAt.atOffset(ZoneOffset.UTC);
    // UPSERT가 캐릭터 행을 잠가 같은 캐릭터의 최신 관측 비교와 추가를 직렬화한다.
    var characterId =
        jdbc.sql(
                """
      INSERT INTO characters (character_id, character_name, server_name, class_name, item_level, combat_power, last_synced_at, profile_refreshed_at, profile_checked_at)
      VALUES (:id, :name, :server, :className, :itemLevel, :combatPower, :fetchedAt, :fetchedAt, :fetchedAt)
      ON CONFLICT (server_name, character_name) DO UPDATE SET
        class_name = EXCLUDED.class_name, item_level = EXCLUDED.item_level,
        combat_power = EXCLUDED.combat_power, last_synced_at = EXCLUDED.last_synced_at,
        profile_refreshed_at = EXCLUDED.profile_refreshed_at, profile_checked_at = EXCLUDED.profile_checked_at
      WHERE characters.last_synced_at <= EXCLUDED.last_synced_at
      RETURNING character_id
      """)
            .param("id", UUID.randomUUID())
            .param("name", profile.characterName())
            .param("server", profile.serverName())
            .param("className", profile.className())
            .param("itemLevel", profile.itemLevel())
            .param("combatPower", profile.combatPower())
            .param("fetchedAt", at)
            .query(UUID.class)
            .optional();
    // 더 최신 수집이 이미 반영됐다면 오래된 결과로 최신 관측을 덮어쓰지 않는다.
    if (characterId.isEmpty()) return;

    var latest =
        jdbc.sql(
                """
      SELECT observation_id, class_name, character_level, item_level, combat_power, expedition_level
      FROM character_observation WHERE character_id = :id
      ORDER BY observed_at DESC, observation_sequence DESC LIMIT 1
      """)
            .param("id", characterId.get())
            .query(
                (rs, row) ->
                    new Latest(
                        rs.getObject("observation_id", UUID.class),
                        new Values(
                            rs.getString("class_name"),
                            rs.getObject("character_level", Integer.class),
                            rs.getBigDecimal("item_level"),
                            rs.getBigDecimal("combat_power"),
                            rs.getObject("expedition_level", Integer.class))))
            .optional();
    var current =
        new Values(
            profile.className(),
            profile.characterLevel(),
            profile.itemLevel(),
            profile.combatPower(),
            profile.expeditionLevel());
    if (profile.title() != null && !profile.title().isBlank())
      jdbc.sql(
              """
        INSERT INTO character_title_observation (character_id, title, first_observed_at, last_observed_at)
        VALUES (:id, :title, :at, :at)
        ON CONFLICT (character_id, title) DO UPDATE SET
          first_observed_at = LEAST(character_title_observation.first_observed_at, EXCLUDED.first_observed_at),
          last_observed_at = GREATEST(character_title_observation.last_observed_at, EXCLUDED.last_observed_at)
        """)
          .param("id", characterId.get())
          .param("title", profile.title().strip())
          .param("at", at)
          .update();
    if (latest.isPresent() && latest.get().values().sameAs(current)) {
      jdbc.sql(
              """
        UPDATE character_observation SET last_confirmed_at = GREATEST(last_confirmed_at, :at)
        WHERE observation_id = :id
        """)
          .param("at", at)
          .param("id", latest.get().id())
          .update();
      return;
    }
    jdbc.sql(
            """
      INSERT INTO character_observation (observation_id, character_id, observed_at, last_confirmed_at,
        class_name, character_level, item_level, combat_power, expedition_level)
      VALUES (:id, :characterId, :at, :at, :className, :characterLevel, :itemLevel, :combatPower, :expeditionLevel)
      """)
        .param("id", UUID.randomUUID())
        .param("characterId", characterId.get())
        .param("at", at)
        .param("className", current.className())
        .param("characterLevel", current.characterLevel())
        .param("itemLevel", current.itemLevel())
        .param("combatPower", current.combatPower())
        .param("expeditionLevel", current.expeditionLevel())
        .update();
  }

  @Override
  @Transactional(readOnly = true, timeout = 3)
  public List<CharacterObservationPoint> latest(
      String characterName, String serverName, int limit) {
    var points =
        jdbc.sql(
                """
      SELECT o.observed_at, o.last_confirmed_at, o.class_name, o.character_level, o.item_level,
        o.combat_power, o.expedition_level
      FROM character_observation o JOIN characters c ON c.character_id = o.character_id
      WHERE c.server_name = :server AND c.character_name = :name
      ORDER BY o.observed_at DESC, o.observation_sequence DESC LIMIT :limit
      """)
            .param("server", serverName)
            .param("name", characterName)
            .param("limit", limit)
            .query(
                (rs, row) ->
                    new CharacterObservationPoint(
                        rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_confirmed_at", OffsetDateTime.class).toInstant(),
                        rs.getString("class_name"),
                        rs.getObject("character_level", Integer.class),
                        rs.getBigDecimal("item_level"),
                        rs.getBigDecimal("combat_power"),
                        rs.getObject("expedition_level", Integer.class)))
            .list();
    var ascending = new ArrayList<>(points);
    java.util.Collections.reverse(ascending);
    return ascending;
  }

  @Override
  @Transactional(readOnly = true, timeout = 3)
  public List<RosterTitle> rosterTitles(
      String serverName, java.util.Collection<String> characterNames) {
    if (characterNames.isEmpty()) return List.of();
    return jdbc.sql(
            """
      SELECT title, first_observed_at, last_observed_at, character_name FROM (
        SELECT t.title, t.first_observed_at, c.character_name,
          MAX(t.last_observed_at) OVER (PARTITION BY t.title) AS last_observed_at,
          ROW_NUMBER() OVER (PARTITION BY t.title ORDER BY t.first_observed_at, c.character_name) AS rn
        FROM character_title_observation t JOIN characters c ON c.character_id = t.character_id
        WHERE c.server_name = :server AND c.character_name IN (:names)
      ) x WHERE rn = 1
      ORDER BY first_observed_at, title
      """)
        .param("server", serverName)
        .param("names", List.copyOf(characterNames))
        .query(
            (rs, row) ->
                new RosterTitle(
                    rs.getString("title"),
                    rs.getObject("first_observed_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("last_observed_at", OffsetDateTime.class).toInstant(),
                    rs.getString("character_name")))
        .list();
  }

  @Override
  public int titleObservedCharacters(
      String serverName, java.util.Collection<String> characterNames) {
    if (characterNames.isEmpty()) return 0;
    return jdbc.sql(
            """
      SELECT COUNT(DISTINCT t.character_id)
      FROM character_title_observation t JOIN characters c ON c.character_id = t.character_id
      WHERE c.server_name = :server AND c.character_name IN (:names)
      """)
        .param("server", serverName)
        .param("names", List.copyOf(characterNames))
        .query(Integer.class)
        .single();
  }

  @Override
  public List<CharacterTitleObservation> titles(String characterName, String serverName) {
    return jdbc.sql(
            """
      SELECT t.title, t.first_observed_at, t.last_observed_at
      FROM character_title_observation t JOIN characters c ON c.character_id = t.character_id
      WHERE c.server_name = :server AND c.character_name = :name
      ORDER BY t.first_observed_at, t.title
      """)
        .param("server", serverName)
        .param("name", characterName)
        .query(
            (rs, row) ->
                new CharacterTitleObservation(
                    rs.getString("title"),
                    rs.getObject("first_observed_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("last_observed_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  private record Latest(UUID id, Values values) {}

  private record Values(
      String className,
      Integer characterLevel,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      Integer expeditionLevel) {
    boolean sameAs(Values other) {
      return Objects.equals(className, other.className)
          && Objects.equals(characterLevel, other.characterLevel)
          && same(itemLevel, other.itemLevel)
          && same(combatPower, other.combatPower)
          && Objects.equals(expeditionLevel, other.expeditionLevel);
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
      return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
  }
}
