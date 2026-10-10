package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterPublicSummaryPort;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PostgresCharacterPublicSummaryRepository implements CharacterPublicSummaryPort {
  private final JdbcClient jdbc;

  PostgresCharacterPublicSummaryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Stored> stored(String characterName) {
    // 캐릭터명은 공식 기준 전체 서버에서 하나다. 여러 행이면 가장 최근에 관측한 행을 쓴다.
    return jdbc.sql(
            """
        SELECT c.character_name, c.server_name, c.class_name, s.character_level, c.item_level,
               c.combat_power, s.ark_passive_title,
               GREATEST(c.last_synced_at, c.profile_refreshed_at, c.roster_scanned_at) AS observed_at
        FROM characters c
        LEFT JOIN LATERAL (
          SELECT character_level, ark_passive_title FROM character_equipment_summary
          WHERE character_id = c.character_id ORDER BY observed_at DESC LIMIT 1) s ON TRUE
        WHERE c.character_name = :name
        ORDER BY observed_at DESC LIMIT 1
        """)
        .param("name", characterName)
        .query(
            (row, index) ->
                new Stored(
                    row.getString("character_name"),
                    row.getString("server_name"),
                    row.getString("class_name"),
                    row.getObject("character_level", Integer.class),
                    row.getBigDecimal("item_level"),
                    row.getBigDecimal("combat_power"),
                    row.getString("ark_passive_title"),
                    row.getObject("observed_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  @Override
  public boolean excludedAnyServer(String characterName) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM public_exclusion WHERE character_name = :name)")
            .param("name", characterName)
            .query(Boolean.class)
            .single());
  }

  @Override
  public boolean excluded(String characterName, String serverName) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                """
            SELECT EXISTS (SELECT 1 FROM public_exclusion
              WHERE character_name = :name AND (server_name IS NULL OR server_name = :server))
            """)
            .param("name", characterName)
            .param("server", serverName)
            .query(Boolean.class)
            .single());
  }
}
