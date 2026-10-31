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

  /** 스냅샷 만들기를 인스턴스 사이에 한 번에 하나로(트랜잭션 잠금). 값은 이 작업 전용 임의 상수. */
  static final long BUILD_LOCK = 0x6c6b5f706f70L;

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public Optional<Latest> latest() {
    return jdbc.sql(
            "SELECT snapshot_date, exclusion_revision FROM stats_population_snapshot_meta"
                + " ORDER BY snapshot_date DESC LIMIT 1")
        .query(
            (row, index) ->
                new Latest(
                    row.getObject("snapshot_date", LocalDate.class),
                    row.getString("exclusion_revision")))
        .optional();
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public String exclusionRevision() {
    return jdbc.sql(StopListRevisionSql.REVISION).query(String.class).single();
  }

  @Override
  @Transactional(timeout = 120)
  public void build(LocalDate date, LocalDate keepFrom) {
    jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) l")
        .param("key", BUILD_LOCK)
        .query(Integer.class)
        .single();
    String revision = jdbc.sql(StopListRevisionSql.REVISION).query(String.class).single();
    // 다른 인스턴스가 방금 같은 날짜를 지금 목록으로 만들었으면 다시 하지 않는다.
    boolean done =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM stats_population_snapshot_meta"
                    + " WHERE snapshot_date = :date AND exclusion_revision = :revision)")
            .param("date", date)
            .param("revision", revision)
            .query(Boolean.class)
            .single();
    if (done) return;
    jdbc.sql(
            "DELETE FROM stats_population_snapshot WHERE snapshot_date = :date OR snapshot_date < :keepFrom")
        .param("date", date)
        .param("keepFrom", keepFrom)
        .update();
    jdbc.sql(
            "DELETE FROM stats_population_snapshot_meta WHERE snapshot_date = :date OR snapshot_date < :keepFrom")
        .param("date", date)
        .param("keepFrom", keepFrom)
        .update();
    jdbc.sql(
            "INSERT INTO stats_population_snapshot_meta (snapshot_date, exclusion_revision, built_at)"
                + " VALUES (:date, :revision, now())")
        .param("date", date)
        .param("revision", revision)
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
