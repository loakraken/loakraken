package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.PopulationSnapshotPort;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전체 인구 스냅샷(B5). 저장 캐릭터 중 아이템 레벨이 있고 처리정지 목록에 없는 캐릭터를 서버·직업·아이템 레벨(10 단위)로 센다. 기간·전투력 조건이 없어 1640
 * 미만으로 원정대 목록에서만 저장한 캐릭터도 들어간다(전투력·역할 값은 쓰지 않음).
 */
@Repository
class PostgresPopulationSnapshotRepository implements PopulationSnapshotPort {
  private static final String BASE =
      "WITH base AS (SELECT c.server_name, c.class_name,"
          + " (FLOOR(c.item_level / 10) * 10)::int AS lv FROM characters c"
          + " WHERE c.item_level IS NOT NULL AND c.server_name IS NOT NULL AND "
          + PostgresStatsRepository.NOT_EXCLUDED
          + ") ";

  private final JdbcClient jdbc;

  PostgresPopulationSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public Optional<LocalDate> latest() {
    return jdbc.sql("SELECT MAX(snapshot_date) FROM stats_population_snapshot")
        .query(LocalDate.class)
        .optional();
  }

  @Override
  @Transactional(timeout = 120)
  public void build(LocalDate date, LocalDate keepFrom) {
    jdbc.sql(
            "DELETE FROM stats_population_snapshot WHERE snapshot_date = :date OR snapshot_date < :keepFrom")
        .param("date", date)
        .param("keepFrom", keepFrom)
        .update();
    jdbc.sql(
            BASE
                + """
            INSERT INTO stats_population_snapshot
              (snapshot_date, server_name, dimension, bucket, character_count)
            SELECT :date, '', 'SERVER', server_name, COUNT(*) FROM base GROUP BY server_name
            UNION ALL
            SELECT :date, '', 'CLASS', class_name, COUNT(*) FROM base
              WHERE class_name IS NOT NULL GROUP BY class_name
            UNION ALL
            SELECT :date, '', 'LEVEL', lv::text, COUNT(*) FROM base GROUP BY lv
            UNION ALL
            SELECT :date, server_name, 'CLASS', class_name, COUNT(*) FROM base
              WHERE class_name IS NOT NULL GROUP BY server_name, class_name
            UNION ALL
            SELECT :date, server_name, 'LEVEL', lv::text, COUNT(*) FROM base GROUP BY server_name, lv
            """)
        .param("date", date)
        .update();
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public List<Row> rows(LocalDate date, String server) {
    return jdbc.sql(
            """
            SELECT dimension, bucket, character_count FROM stats_population_snapshot
            WHERE snapshot_date = :date AND (server_name = :server OR (dimension = 'SERVER' AND server_name = ''))
            ORDER BY dimension, character_count DESC, bucket
            """)
        .param("date", date)
        .param("server", server)
        .query(
            (row, index) ->
                new Row(
                    row.getString("dimension"),
                    row.getString("bucket"),
                    row.getLong("character_count")))
        .list();
  }
}
