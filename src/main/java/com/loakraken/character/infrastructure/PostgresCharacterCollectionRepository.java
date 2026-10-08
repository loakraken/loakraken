package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCollectionPort;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresCharacterCollectionRepository implements CharacterCollectionPort {
  private final JdbcClient jdbc;

  PostgresCharacterCollectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Target> nextProfileDue(BigDecimal minItemLevel, Instant staleBefore) {
    return jdbc.sql(
            """
        SELECT character_name, server_name FROM characters
        WHERE item_level >= :min AND (profile_refreshed_at IS NULL OR profile_refreshed_at < :stale)
        ORDER BY profile_refreshed_at NULLS FIRST, item_level DESC, character_name
        LIMIT 1
        """)
        .param("min", minItemLevel)
        .param("stale", utc(staleBefore))
        .query((row, index) -> new Target(row.getString(1), row.getString(2)))
        .optional();
  }

  @Override
  public Optional<Target> nextRosterDue(Instant staleBefore) {
    return jdbc.sql(
            """
        SELECT character_name, server_name FROM characters
        WHERE roster_scanned_at IS NULL OR roster_scanned_at < :stale
        ORDER BY roster_scanned_at NULLS FIRST, item_level DESC, character_name
        LIMIT 1
        """)
        .param("stale", utc(staleBefore))
        .query((row, index) -> new Target(row.getString(1), row.getString(2)))
        .optional();
  }

  @Override
  @Transactional(timeout = 5)
  public int saveRoster(List<CharacterSibling> siblings, Instant now) {
    int discovered = 0;
    for (var sibling : siblings) {
      if (sibling == null
          || sibling.characterName() == null
          || sibling.serverName() == null
          || sibling.className() == null
          || sibling.itemLevel() == null) continue;
      // 새 캐릭터는 프로필 갱신 전까지 전투력·포지션이 비어 있다. 기존 캐릭터는 아이템 레벨·직업만 원정대 값으로 맞춘다.
      Boolean inserted =
          jdbc.sql(
                  """
          INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
                                  last_synced_at, discovered_at, roster_scanned_at)
          VALUES (:id, :name, :server, :className, :itemLevel, :now, :now, :now)
          ON CONFLICT (server_name, character_name) DO UPDATE SET
            class_name = EXCLUDED.class_name, item_level = EXCLUDED.item_level,
            roster_scanned_at = EXCLUDED.roster_scanned_at
          RETURNING (xmax = 0)
          """)
              .param("id", UUID.randomUUID())
              .param("name", sibling.characterName())
              .param("server", sibling.serverName())
              .param("className", sibling.className())
              .param("itemLevel", sibling.itemLevel())
              .param("now", utc(now))
              .query(Boolean.class)
              .single();
      if (Boolean.TRUE.equals(inserted)) discovered++;
    }
    return discovered;
  }

  @Override
  public void markProfileRefreshed(Target target, String position, Instant now) {
    jdbc.sql(
            """
        UPDATE characters SET profile_refreshed_at = :now, position = :position
        WHERE server_name = :server AND character_name = :name
        """)
        .param("now", utc(now))
        .param("position", position)
        .param("server", target.serverName())
        .param("name", target.characterName())
        .update();
  }

  @Override
  public void markProfileMissing(Target target, Instant now) {
    jdbc.sql(
            "UPDATE characters SET profile_refreshed_at = :now WHERE server_name = :server AND character_name = :name")
        .param("now", utc(now))
        .param("server", target.serverName())
        .param("name", target.characterName())
        .update();
  }

  @Override
  public void markRosterScanned(Target target, Instant now) {
    jdbc.sql(
            "UPDATE characters SET roster_scanned_at = :now WHERE server_name = :server AND character_name = :name")
        .param("now", utc(now))
        .param("server", target.serverName())
        .param("name", target.characterName())
        .update();
  }

  private static OffsetDateTime utc(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}
