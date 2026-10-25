package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterSnapshotHistoryPort;
import com.loakraken.character.application.CharacterSnapshotPage;
import com.loakraken.character.application.SnapshotHistoryQuery;
import com.loakraken.character.domain.CharacterSnapshotSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
class PostgresCharacterSnapshotHistory implements CharacterSnapshotHistoryPort {
  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  PostgresCharacterSnapshotHistory(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true, timeout = 3)
  public CharacterSnapshotPage find(SnapshotHistoryQuery query) {
    CursorBoundary boundary = null;
    if (query.cursor() != null) {
      boundary =
          jdbc.sql(
                  """
          SELECT s.fetched_at, s.snapshot_sequence
          FROM character_snapshot s JOIN characters c USING (character_id)
          WHERE s.snapshot_id = :cursor AND c.character_name = :name AND c.server_name = :server
          """
                      + StopListSql.NOT_EXCLUDED)
              .param("cursor", query.cursor())
              .param("name", query.characterName())
              .param("server", query.serverName())
              .query(
                  (row, index) ->
                      new CursorBoundary(
                          row.getObject("fetched_at", OffsetDateTime.class),
                          row.getLong("snapshot_sequence")))
              .optional()
              .orElseThrow(
                  () -> new CharacterException(CharacterErrorCode.INVALID_SNAPSHOT_CURSOR));
    }
    String cursorClause =
        boundary == null
            ? ""
            : " AND (s.fetched_at, s.snapshot_sequence) < (:beforeTime, :beforeSequence)";
    var statement =
        jdbc.sql(
                """
        SELECT s.snapshot_id, s.fetched_at, s.schema_version,
               CAST(s.armory_json -> 'ArmoryProfile' AS text) AS profile_json
        FROM character_snapshot s JOIN characters c USING (character_id)
        WHERE c.character_name = :name AND c.server_name = :server
        """
                    + StopListSql.NOT_EXCLUDED
                    + cursorClause
                    + " ORDER BY s.fetched_at DESC, s.snapshot_sequence DESC LIMIT :size")
            .param("name", query.characterName())
            .param("server", query.serverName())
            .param("size", query.limit() + 1);
    if (boundary != null) {
      statement
          .param("beforeTime", boundary.fetchedAt())
          .param("beforeSequence", boundary.sequence());
    }
    var rows = statement.query((row, index) -> read(row, query)).list();
    boolean hasMore = rows.size() > query.limit();
    var items = hasMore ? rows.subList(0, query.limit()) : rows;
    return new CharacterSnapshotPage(items, hasMore ? items.getLast().snapshotId() : null);
  }

  private CharacterSnapshotSummary read(ResultSet row, SnapshotHistoryQuery query)
      throws SQLException {
    if (row.getInt("schema_version") != 1) throw unavailable();
    String json = row.getString("profile_json");
    if (json == null) throw unavailable();
    try {
      var profile = LostArkProfileMapper.from(mapper.readValue(json, LostArkProfileResponse.class));
      if (!profile.characterName().equals(query.characterName())
          || !profile.serverName().equals(query.serverName())) throw unavailable();
      return new CharacterSnapshotSummary(
          row.getObject("snapshot_id", UUID.class),
          row.getObject("fetched_at", OffsetDateTime.class).toInstant(),
          profile.className(),
          profile.itemLevel(),
          profile.combatPower());
    } catch (JacksonException | CharacterException exception) {
      // 저장된 기록의 손상을 외부 API 오류나 빈 이력으로 표시하지 않는다.
      throw unavailable();
    }
  }

  private CharacterException unavailable() {
    return new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_UNAVAILABLE);
  }

  private record CursorBoundary(OffsetDateTime fetchedAt, long sequence) {}
}
