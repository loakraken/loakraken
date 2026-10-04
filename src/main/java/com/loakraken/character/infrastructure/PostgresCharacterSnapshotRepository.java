package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterSnapshotPort;
import com.loakraken.character.application.FetchedCharacter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresCharacterSnapshotRepository implements CharacterSnapshotPort {
  private final JdbcClient jdbc;

  PostgresCharacterSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 3)
  public void save(FetchedCharacter fetched, Instant fetchedAt) {
    var profile = fetched.details().profile();
    // UPSERT가 캐릭터 행을 잠가 최신 스냅샷 비교와 추가를 같은 트랜잭션 안에서 직렬화한다.
    var characterId =
        jdbc.sql(
                """
      INSERT INTO characters (character_id, character_name, server_name, class_name, item_level, combat_power, last_synced_at)
      VALUES (:id, :name, :server, :className, :itemLevel, :combatPower, :fetchedAt)
      ON CONFLICT (server_name, character_name) DO UPDATE SET
        class_name = EXCLUDED.class_name, item_level = EXCLUDED.item_level,
        combat_power = EXCLUDED.combat_power, last_synced_at = EXCLUDED.last_synced_at
      WHERE characters.last_synced_at <= EXCLUDED.last_synced_at
      RETURNING character_id
      """)
            .param("id", UUID.randomUUID())
            .param("name", profile.characterName())
            .param("server", profile.serverName())
            .param("className", profile.className())
            .param("itemLevel", profile.itemLevel())
            .param("combatPower", profile.combatPower())
            .param("fetchedAt", fetchedAt.atOffset(ZoneOffset.UTC))
            .query(UUID.class)
            .optional();
    if (characterId.isEmpty()) return;

    boolean unchanged =
        jdbc.sql(
                """
      SELECT armory_json = CAST(:armory AS jsonb) AND siblings_json = CAST(:siblings AS jsonb)
      FROM character_snapshot WHERE character_id = :id
              ORDER BY fetched_at DESC, snapshot_sequence DESC LIMIT 1
      """)
            .param("armory", fetched.armoryJson())
            .param("siblings", fetched.siblingsJson())
            .param("id", characterId.get())
            .query(Boolean.class)
            .optional()
            .orElse(false);
    if (unchanged) return;

    jdbc.sql(
            """
      INSERT INTO character_snapshot (snapshot_id, character_id, schema_version, armory_json, siblings_json, fetched_at)
      VALUES (:id, :characterId, 1, CAST(:armory AS jsonb), CAST(:siblings AS jsonb), :fetchedAt)
      """)
        .param("id", UUID.randomUUID())
        .param("characterId", characterId.get())
        .param("armory", fetched.armoryJson())
        .param("siblings", fetched.siblingsJson())
        .param("fetchedAt", fetchedAt.atOffset(ZoneOffset.UTC))
        .update();
  }
}
