package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterSuggestPort;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PostgresCharacterSuggestRepository implements CharacterSuggestPort {
  private final JdbcClient jdbc;

  PostgresCharacterSuggestRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Suggestion> suggest(String prefix, int limit) {
    // idx_characters_name_prefix(text_pattern_ops)로 앞부분 일치를 찾는다(V18). 처리정지 목록은 서버를 비운 항목이면 모든 서버를 뺀다.
    return jdbc.sql(
            """
            SELECT c.character_name, c.server_name, c.class_name, c.item_level, c.image_url
            FROM characters c
            WHERE c.character_name LIKE :pattern ESCAPE '\\'
              AND NOT EXISTS (SELECT 1 FROM public_exclusion e
                WHERE e.character_name = c.character_name
                  AND (e.server_name IS NULL OR e.server_name = c.server_name))
            ORDER BY c.item_level DESC, c.character_name, c.server_name
            LIMIT :limit
            """)
        .param("pattern", escape(prefix) + "%")
        .param("limit", limit)
        .query(
            (row, index) ->
                new Suggestion(
                    row.getString("character_name"),
                    row.getString("server_name"),
                    row.getString("class_name"),
                    row.getBigDecimal("item_level"),
                    row.getString("image_url")))
        .list();
  }

  /** LIKE 특수 문자(\ % _)를 글자 그대로 찾게 한다. */
  static String escape(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
