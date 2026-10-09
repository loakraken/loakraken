package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterRetentionPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresCharacterRetentionRepository implements CharacterRetentionPort {
  private final JdbcClient jdbc;

  PostgresCharacterRetentionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 30)
  public int deleteUnobservedCharacters(Instant cutoff, int limit) {
    return jdbc.sql(
            """
        DELETE FROM characters WHERE character_id IN (
          SELECT character_id FROM characters
          WHERE GREATEST(last_synced_at, profile_refreshed_at, roster_scanned_at) < :cutoff
          LIMIT :limit)
        """)
        .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
        .param("limit", limit)
        .update();
  }

  @Override
  @Transactional(timeout = 30)
  public int deleteViewsBefore(LocalDate cutoff, int limit) {
    return jdbc.sql(
            """
        DELETE FROM character_view_daily WHERE ctid IN (
          SELECT ctid FROM character_view_daily WHERE view_date < :cutoff LIMIT :limit)
        """)
        .param("cutoff", cutoff)
        .param("limit", limit)
        .update();
  }
}
