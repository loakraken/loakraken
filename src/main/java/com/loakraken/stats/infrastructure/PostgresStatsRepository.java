package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.StatsPort;
import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules.Metric;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * characters를 읽기 전용으로 집계한다. 모집단 = 프로필(전투력)을 확인했고 since 이후 갱신한 캐릭터. 모든 필터 값은 바인딩 파라미터이고, 열 이름은
 * Metric enum에서만 온다.
 */
@Repository
@Transactional(readOnly = true, timeout = 5)
class PostgresStatsRepository implements StatsPort {
  private final JdbcClient jdbc;

  PostgresStatsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Summary summary(StatsConditions conditions, Instant since) {
    var population = population(conditions, since);
    return population
        .statement(
            """
        SELECT COUNT(*) AS n, AVG(item_level) AS il_mean,
          percentile_cont(0.5) WITHIN GROUP (ORDER BY item_level) AS il_median,
          percentile_cont(0.5) WITHIN GROUP (ORDER BY combat_power) AS cp_median,
          percentile_cont(0.9) WITHIN GROUP (ORDER BY combat_power) AS cp_p90,
          COUNT(*) FILTER (WHERE position = 'DEALER') AS dealers,
          COUNT(*) FILTER (WHERE position = 'SUPPORT') AS supports,
          AVG(gem_mean_level) AS gem_mean, COUNT(gem_mean_level) AS gem_n
        FROM pop
        """,
            jdbc)
        .query(
            (row, index) ->
                new Summary(
                    row.getLong("n"),
                    round(row.getBigDecimal("il_mean")),
                    round(row.getBigDecimal("il_median")),
                    round(row.getBigDecimal("cp_median")),
                    round(row.getBigDecimal("cp_p90")),
                    row.getLong("dealers"),
                    row.getLong("supports"),
                    round(row.getBigDecimal("gem_mean")),
                    row.getLong("gem_n")))
        .single();
  }

  @Override
  public List<ClassRow> classes(StatsConditions conditions, Instant since) {
    return population(conditions, since)
        .statement(
            """
        SELECT class_name, COUNT(*) AS n,
          COUNT(*) FILTER (WHERE position = 'DEALER') AS dealers,
          COUNT(*) FILTER (WHERE position = 'SUPPORT') AS supports,
          AVG(item_level) AS il_mean, AVG(gem_mean_level) AS gem_mean,
          percentile_cont(ARRAY[0.1, 0.25, 0.5, 0.75, 0.9]) WITHIN GROUP (ORDER BY combat_power) AS cp
        FROM pop GROUP BY class_name ORDER BY n DESC, class_name
        """,
            jdbc)
        .query(
            (row, index) ->
                new ClassRow(
                    row.getString("class_name"),
                    row.getLong("n"),
                    row.getLong("dealers"),
                    row.getLong("supports"),
                    round(row.getBigDecimal("il_mean")),
                    round(row.getBigDecimal("gem_mean")),
                    percentiles(row.getArray("cp"))))
        .list();
  }

  @Override
  public List<ServerRow> servers(StatsConditions conditions, Instant since) {
    // 모집단이 0명인 서버도 알고 있는 캐릭터·원정대 수는 보여 준다.
    var rows =
        population(conditions, since)
            .statement(
                """
            , known AS (
              SELECT server_name, COUNT(*) AS known, COUNT(DISTINCT roster_key) AS rosters
              FROM characters GROUP BY server_name),
            stat AS (
              SELECT server_name, COUNT(*) AS n,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY item_level) AS il_median,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY combat_power) AS cp_median
              FROM pop GROUP BY server_name)
            SELECT k.server_name, COALESCE(s.n, 0) AS n, s.il_median, s.cp_median, k.known, k.rosters
            FROM known k LEFT JOIN stat s ON s.server_name = k.server_name
            ORDER BY n DESC, k.known DESC, k.server_name
            """,
                jdbc)
            .query(
                (row, index) ->
                    new ServerRow(
                        row.getString("server_name"),
                        row.getLong("n"),
                        round(row.getBigDecimal("il_median")),
                        round(row.getBigDecimal("cp_median")),
                        row.getLong("known"),
                        row.getLong("rosters")))
            .list();
    if (conditions.server() == null) return rows;
    return rows.stream().filter(row -> row.serverName().equals(conditions.server())).toList();
  }

  @Override
  public Range range(StatsConditions conditions, Instant since, Metric metric) {
    String column = metric.column();
    return population(conditions, since)
        .statement(
            "SELECT COUNT(*) AS n, MIN(" + column + ") AS lo, MAX(" + column + ") AS hi FROM pop",
            jdbc)
        .query(
            (row, index) ->
                new Range(row.getLong("n"), row.getBigDecimal("lo"), row.getBigDecimal("hi")))
        .single();
  }

  @Override
  public List<Bin> histogram(
      StatsConditions conditions, Instant since, Metric metric, BigDecimal width) {
    String column = metric.column();
    return population(conditions, since)
        .statement(
            "SELECT FLOOR("
                + column
                + " / :width) * :width AS lower_bound, COUNT(*) AS n FROM pop GROUP BY 1 ORDER BY 1",
            jdbc)
        .param("width", width)
        .query(
            (row, index) ->
                new Bin(row.getBigDecimal("lower_bound").stripTrailingZeros(), row.getLong("n")))
        .list();
  }

  @Override
  public Standing standing(
      StatsConditions conditions, Instant since, Metric metric, BigDecimal value) {
    String column = metric.column();
    return population(conditions, since)
        .statement(
            "SELECT COUNT(*) FILTER (WHERE "
                + column
                + " > :value) AS greater, COUNT(*) AS n FROM pop",
            jdbc)
        .param("value", value)
        .query((row, index) -> new Standing(row.getLong("greater"), row.getLong("n")))
        .single();
  }

  @Override
  public RankingPage ranking(
      StatsConditions conditions, Instant since, Metric metric, int offset, int limit) {
    String column = metric.column();
    String tie = metric == Metric.COMBAT_POWER ? "item_level" : "combat_power";
    var rows = new ArrayList<RankingRow>();
    long[] total = {0};
    population(conditions, since, true)
        .statement(
            "SELECT character_name, server_name, class_name, position, item_level, combat_power,"
                + " RANK() OVER (ORDER BY "
                + column
                + " DESC) AS rnk, COUNT(*) OVER () AS total FROM pop ORDER BY "
                + column
                + " DESC, "
                + tie
                + " DESC, character_name, server_name LIMIT :limit OFFSET :offset",
            jdbc)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (ResultSet row) -> {
              total[0] = row.getLong("total");
              rows.add(
                  new RankingRow(
                      row.getLong("rnk"),
                      row.getString("character_name"),
                      row.getString("server_name"),
                      row.getString("class_name"),
                      row.getString("position"),
                      row.getBigDecimal("item_level"),
                      row.getBigDecimal("combat_power")));
            });
    // 요청 페이지가 범위를 넘으면 행이 없어 total을 따로 센다.
    if (rows.isEmpty())
      total[0] =
          population(conditions, since, true)
              .statement("SELECT COUNT(*) FROM pop", jdbc)
              .query(Long.class)
              .single();
    return new RankingPage(total[0], rows);
  }

  @Override
  public Coverage coverage() {
    return jdbc.sql(
            """
        SELECT COUNT(*) FILTER (WHERE combat_power IS NOT NULL) AS observed, COUNT(*) AS known,
          COUNT(DISTINCT roster_key) AS accounts
        FROM characters
        """)
        .query(
            (row, index) ->
                new Coverage(
                    row.getLong("observed"), row.getLong("known"), row.getLong("accounts")))
        .single();
  }

  @Override
  public Optional<Target> target(String characterName) {
    return jdbc.sql(
            """
        SELECT character_name, server_name, class_name, position, item_level, combat_power,
          profile_refreshed_at
        FROM characters WHERE character_name = :name AND combat_power IS NOT NULL
        ORDER BY profile_refreshed_at DESC NULLS LAST LIMIT 1
        """)
        .param("name", characterName)
        .query(
            (row, index) ->
                new Target(
                    row.getString("character_name"),
                    row.getString("server_name"),
                    row.getString("class_name"),
                    row.getString("position"),
                    row.getBigDecimal("item_level"),
                    row.getBigDecimal("combat_power"),
                    instant(row.getObject("profile_refreshed_at", OffsetDateTime.class))))
        .optional();
  }

  @Override
  public boolean contains(StatsConditions conditions, Instant since, Target target) {
    return population(conditions, since)
        .statement(
            "SELECT EXISTS (SELECT 1 FROM pop WHERE server_name = :tServer AND character_name = :tName)",
            jdbc)
        .param("tServer", target.serverName())
        .param("tName", target.characterName())
        .query(Boolean.class)
        .single();
  }

  /** 모집단 CTE와 그 바인딩 값. */
  private static Population population(StatsConditions c, Instant since) {
    return population(c, since, false);
  }

  /**
   * excludePublic이면 공개 제외 목록(public_exclusion)의 캐릭터를 뺀다. 랭킹 목록에만 쓰고, 통계 집계와 본인 순위 조회에는 쓰지 않는다(PM 총괄
   * 결정 2026-10-08).
   */
  private static Population population(StatsConditions c, Instant since, boolean excludePublic) {
    var where =
        new StringBuilder(
            "c.combat_power IS NOT NULL AND c.profile_refreshed_at >= :since AND c.item_level >= :ilMin");
    var params = new HashMap<String, Object>();
    params.put("since", since.atOffset(ZoneOffset.UTC));
    params.put("ilMin", c.itemLevelMin());
    if (c.itemLevelMax() != null) {
      where.append(" AND c.item_level <= :ilMax");
      params.put("ilMax", c.itemLevelMax());
    }
    if (c.combatPowerMin() != null) {
      where.append(" AND c.combat_power >= :cpMin");
      params.put("cpMin", c.combatPowerMin());
    }
    if (c.combatPowerMax() != null) {
      where.append(" AND c.combat_power <= :cpMax");
      params.put("cpMax", c.combatPowerMax());
    }
    if (c.server() != null) {
      where.append(" AND c.server_name = :server");
      params.put("server", c.server());
    }
    if (c.role() != null) {
      where.append(" AND c.position = :role");
      params.put("role", c.role().name());
    }
    if (c.className() != null) {
      where.append(" AND c.class_name = :className");
      params.put("className", c.className());
    }
    if (c.representativeOnly())
      // 같은 원정대(같은 서버·같은 roster_key)에서 아이템 레벨이 가장 높은 캐릭터. 동률이면 이름순 첫 캐릭터.
      // 텍스트 블록은 앞 공백을 지우므로 구분 공백을 따로 붙인다.
      where
          .append(' ')
          .append(
              """
           AND (c.roster_key IS NULL OR NOT EXISTS (
             SELECT 1 FROM characters o
             WHERE o.roster_key = c.roster_key AND o.server_name = c.server_name
               AND (o.item_level > c.item_level
                    OR (o.item_level = c.item_level AND o.character_name < c.character_name))))""");
    if (excludePublic)
      where.append(
          " AND NOT EXISTS (SELECT 1 FROM public_exclusion e WHERE e.character_name = c.character_name"
              + " AND (e.server_name IS NULL OR e.server_name = c.server_name))");
    String cte =
        "WITH pop AS (SELECT c.character_name, c.server_name, c.class_name, c.item_level,"
            + " c.combat_power, c.position, c.gem_mean_level FROM characters c WHERE "
            + where
            + ") ";
    return new Population(cte, params);
  }

  private record Population(String cte, Map<String, Object> params) {
    JdbcClient.StatementSpec statement(String sql, JdbcClient jdbc) {
      return jdbc.sql(cte + sql).params(params);
    }
  }

  private static List<BigDecimal> percentiles(Array array) throws SQLException {
    if (array == null) return List.of();
    var values = (Object[]) array.getArray();
    var result = new ArrayList<BigDecimal>();
    for (var value : values)
      result.add(value == null ? null : round(new BigDecimal(value.toString())));
    return result;
  }

  private static BigDecimal round(BigDecimal value) {
    return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
