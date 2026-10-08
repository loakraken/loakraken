package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterViewPort;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PostgresCharacterViewRepository implements CharacterViewPort {
  private final JdbcClient jdbc;

  PostgresCharacterViewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void increment(String characterName, LocalDate date) {
    jdbc.sql(
            """
        INSERT INTO character_view_daily (character_name, view_date, view_count)
        VALUES (:name, :date, 1)
        ON CONFLICT (character_name, view_date)
        DO UPDATE SET view_count = character_view_daily.view_count + 1
        """)
        .param("name", characterName)
        .param("date", date)
        .update();
  }

  @Override
  public Map<LocalDate, Long> daily(String characterName, LocalDate from, LocalDate to) {
    var result = new LinkedHashMap<LocalDate, Long>();
    jdbc.sql(
            """
        SELECT view_date, view_count FROM character_view_daily
        WHERE character_name = :name AND view_date BETWEEN :from AND :to
        ORDER BY view_date
        """)
        .param("name", characterName)
        .param("from", from)
        .param("to", to)
        .query(
            (row, index) ->
                Map.entry(row.getObject("view_date", LocalDate.class), row.getLong("view_count")))
        .list()
        .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
    return result;
  }

  @Override
  public long total(String characterName) {
    return jdbc.sql(
            "SELECT COALESCE(SUM(view_count), 0) FROM character_view_daily WHERE character_name = :name")
        .param("name", characterName)
        .query(Long.class)
        .single();
  }
}
