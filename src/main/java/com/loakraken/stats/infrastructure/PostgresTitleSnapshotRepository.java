package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.StatsPort;
import com.loakraken.stats.application.TitleSnapshotPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 칭호 장착 확인 일별 스냅숏(title_equip_daily·title_equip_daily_meta). server_key '' = 전체. */
@Repository
class PostgresTitleSnapshotRepository implements TitleSnapshotPort {
  /** 스냅숏 쓰기를 인스턴스 사이에 한 번에 하나로(트랜잭션 잠금). 값은 이 작업 전용 임의 상수. */
  static final long BUILD_LOCK = 0x6c6b5f7469746cL;

  private final JdbcClient jdbc;

  PostgresTitleSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public Optional<LocalDate> latest() {
    return jdbc.sql(
            "SELECT snapshot_date FROM title_equip_daily_meta WHERE server_key = ''"
                + " ORDER BY snapshot_date DESC LIMIT 1")
        .query(LocalDate.class)
        .optional();
  }

  @Override
  @Transactional(timeout = 120)
  public void replace(
      LocalDate date,
      Instant builtAt,
      Map<String, StatsPort.TitleEquip> byServer,
      LocalDate keepFrom) {
    jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) l")
        .param("key", BUILD_LOCK)
        .query(Integer.class)
        .single();
    for (var table : List.of("title_equip_daily", "title_equip_daily_meta"))
      jdbc.sql("DELETE FROM " + table + " WHERE snapshot_date = :date OR snapshot_date < :keepFrom")
          .param("date", date)
          .param("keepFrom", keepFrom)
          .update();
    for (var entry : byServer.entrySet()) {
      jdbc.sql(
              "INSERT INTO title_equip_daily_meta (snapshot_date, server_key, population, built_at)"
                  + " VALUES (:date, :key, :population, :builtAt)")
          .param("date", date)
          .param("key", entry.getKey())
          .param("population", entry.getValue().population())
          .param("builtAt", Timestamp.from(builtAt))
          .update();
      for (var row : entry.getValue().titles())
        jdbc.sql(
                """
                INSERT INTO title_equip_daily
                  (snapshot_date, server_key, title, equipped_rosters, observed_rosters,
                   new_rosters_30d, observed_characters)
                VALUES (:date, :key, :title, :equipped, :observed, :newRosters, :characters)
                """)
            .param("date", date)
            .param("key", entry.getKey())
            .param("title", row.title())
            .param("equipped", row.rosters())
            .param("observed", row.observedRosters())
            .param("newRosters", row.newRosters())
            .param("characters", row.observedCharacters())
            .update();
    }
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public Optional<Snapshot> find(LocalDate date, String serverKey) {
    var meta =
        jdbc.sql(
                "SELECT population, built_at FROM title_equip_daily_meta"
                    + " WHERE snapshot_date = :date AND server_key = :key")
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
                + " ORDER BY snapshot_date DESC LIMIT :limit")
        .param("key", serverKey)
        .param("limit", limit)
        .query(LocalDate.class)
        .list();
  }
}
