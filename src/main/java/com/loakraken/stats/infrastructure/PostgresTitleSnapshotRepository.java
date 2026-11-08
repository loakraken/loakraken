package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.StatsPort;
import com.loakraken.stats.application.TitleSnapshotPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 칭호 장착 확인 일별 스냅숏(title_equip_daily·title_equip_daily_meta). server_key '' = 전체. */
@Repository
class PostgresTitleSnapshotRepository implements TitleSnapshotPort {
  /** 스냅숏 쓰기를 인스턴스 사이에 한 번에 하나로(트랜잭션 잠금). 값은 이 작업 전용 임의 상수. */
  static final long BUILD_LOCK = 0x6c6b5f7469746cL;

  /** 지금 처리정지 목록 값(같은 문장·같은 스냅숏 안에서 읽는 부분 질의). */
  private static final String CURRENT = "(" + StopListRevisionSql.REVISION + ")";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  PostgresTitleSnapshotRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public Optional<LocalDate> latest() {
    return jdbc.sql(
            "SELECT snapshot_date FROM title_equip_daily_meta WHERE server_key = ''"
                + " AND exclusion_revision = "
                + CURRENT
                + " ORDER BY snapshot_date DESC LIMIT 1")
        .query(LocalDate.class)
        .optional();
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public boolean hasStale() {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM title_equip_daily_meta WHERE exclusion_revision <> "
                + CURRENT
                + ")")
        .query(Boolean.class)
        .single();
  }

  @Override
  @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 120)
  public int replace(
      LocalDate date,
      Instant builtAt,
      Supplier<Map<String, StatsPort.TitleEquip>> compute,
      LocalDate keepFrom) {
    // 집계(같은 트랜잭션에 들어오는 통계 질의)와 아래 목록 값이 한 스냅숏을 본다. 다른 인스턴스와 겹치면 쓰기에서 직렬화 오류로 되돌아가고 다음 주기에 다시 한다.
    var byServer = compute.get();
    var revision = jdbc.sql(StopListRevisionSql.REVISION).query(String.class).single();
    jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) l")
        .param("key", BUILD_LOCK)
        .query(Integer.class)
        .single();
    // 처리정지 목록이 바뀌었으면 지난 목록으로 만든 날짜(그 캐릭터가 세어진 값)를 모두 지운다. 같은 트랜잭션·잠금 안이라 지운 뒤 다시 넣기까지 한 번에.
    var stale =
        jdbc.sql(
                "SELECT DISTINCT snapshot_date FROM title_equip_daily_meta"
                    + " WHERE exclusion_revision <> :revision")
            .param("revision", revision)
            .query(LocalDate.class)
            .list();
    if (!stale.isEmpty())
      for (var table : List.of("title_equip_daily", "title_equip_daily_meta"))
        jdbc.sql("DELETE FROM " + table + " WHERE snapshot_date IN (:dates)")
            .param("dates", stale)
            .update();
    for (var table : List.of("title_equip_daily", "title_equip_daily_meta"))
      jdbc.sql("DELETE FROM " + table + " WHERE snapshot_date = :date OR snapshot_date < :keepFrom")
          .param("date", date)
          .param("keepFrom", keepFrom)
          .update();
    // 메타·칭호 줄을 각각 한 번의 일괄 넣기로(하루 수백 줄, 왕복 수를 줄임).
    var metas = new ArrayList<MapSqlParameterSource>();
    var rows = new ArrayList<MapSqlParameterSource>();
    for (var entry : byServer.entrySet()) {
      metas.add(
          new MapSqlParameterSource()
              .addValue("date", date)
              .addValue("key", entry.getKey())
              .addValue("population", entry.getValue().population())
              .addValue("builtAt", Timestamp.from(builtAt))
              .addValue("revision", revision));
      for (var row : entry.getValue().titles())
        rows.add(
            new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("key", entry.getKey())
                .addValue("title", row.title())
                .addValue("equipped", row.rosters())
                .addValue("observed", row.observedRosters())
                .addValue("newRosters", row.newRosters())
                .addValue("characters", row.observedCharacters()));
    }
    batch.batchUpdate(
        "INSERT INTO title_equip_daily_meta"
            + " (snapshot_date, server_key, population, built_at, exclusion_revision)"
            + " VALUES (:date, :key, :population, :builtAt, :revision)",
        metas.toArray(MapSqlParameterSource[]::new));
    if (!rows.isEmpty())
      batch.batchUpdate(
          """
          INSERT INTO title_equip_daily
            (snapshot_date, server_key, title, equipped_rosters, observed_rosters,
             new_rosters_30d, observed_characters)
          VALUES (:date, :key, :title, :equipped, :observed, :newRosters, :characters)
          """,
          rows.toArray(MapSqlParameterSource[]::new));
    return stale.size();
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
  public Optional<Snapshot> find(LocalDate date, String serverKey) {
    // 메타(목록 값 확인 포함)와 칭호 행을 한 스냅숏에서 읽는다: 그 사이 다시 만들기가 끼어도 섞이지 않는다.
    var meta =
        jdbc.sql(
                "SELECT population, built_at FROM title_equip_daily_meta"
                    + " WHERE snapshot_date = :date AND server_key = :key"
                    + " AND exclusion_revision = "
                    + CURRENT)
            .param("date", date)
            .param("key", serverKey)
            .query(
                (row, index) ->
                    new Meta(row.getLong("population"), row.getTimestamp("built_at").toInstant()))
            .optional();
    if (meta.isEmpty()) return Optional.empty();
    var rows =
        jdbc.sql(
                """
                SELECT title, equipped_rosters, observed_rosters, new_rosters_30d, observed_characters
                FROM title_equip_daily WHERE snapshot_date = :date AND server_key = :key
                ORDER BY equipped_rosters DESC, observed_rosters DESC, title COLLATE "C"
                """)
            .param("date", date)
            .param("key", serverKey)
            .query(
                (row, index) ->
                    new StatsPort.TitleEquipRow(
                        row.getString("title"),
                        row.getLong("equipped_rosters"),
                        row.getLong("observed_rosters"),
                        row.getLong("new_rosters_30d"),
                        row.getLong("observed_characters")))
            .list();
    return Optional.of(
        new Snapshot(
            meta.get().builtAt(), new StatsPort.TitleEquip(meta.get().population(), rows)));
  }

  private record Meta(long population, Instant builtAt) {}

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public List<LocalDate> dates(String serverKey, int limit) {
    return jdbc.sql(
            "SELECT snapshot_date FROM title_equip_daily_meta WHERE server_key = :key"
                + " AND exclusion_revision = "
                + CURRENT
                + " ORDER BY snapshot_date DESC LIMIT :limit")
        .param("key", serverKey)
        .param("limit", limit)
        .query(LocalDate.class)
        .list();
  }
}
