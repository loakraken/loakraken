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

  /**
   * 정리 배치는 하위 표까지 함께 지워(ON DELETE CASCADE) 운영 DB 실행 역할의 문장 한도(기본 15초)보다 길 수 있다. 이 트랜잭션 안에서만 앱 한도와
   * 같은 30초로 둔다.
   */
  private void allowBatchStatementTime() {
    jdbc.sql("SET LOCAL statement_timeout = '30s'").update();
  }

  @Override
  @Transactional(timeout = 30)
  public int deleteUnobservedCharacters(Instant cutoff, int limit) {
    allowBatchStatementTime();
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
    allowBatchStatementTime();
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
