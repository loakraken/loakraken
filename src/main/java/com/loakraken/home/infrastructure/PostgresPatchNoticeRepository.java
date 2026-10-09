package com.loakraken.home.infrastructure;

import com.loakraken.home.application.PatchNoticePort;
import com.loakraken.home.domain.PatchNotice;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresPatchNoticeRepository implements PatchNoticePort {
  private final JdbcClient jdbc;

  PostgresPatchNoticeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 5)
  public void save(List<PatchNotice> notices, Instant observedAt) {
    var at = observedAt.atOffset(ZoneOffset.UTC);
    for (var notice : notices)
      jdbc.sql(
              """
          INSERT INTO patch_notice (notice_id, title, category, published_at, url, first_observed_at, last_observed_at)
          VALUES (:id, :title, :category, :published, :url, :at, :at)
          ON CONFLICT (notice_id) DO UPDATE SET
            title = EXCLUDED.title, category = EXCLUDED.category, url = EXCLUDED.url,
            last_observed_at = GREATEST(patch_notice.last_observed_at, EXCLUDED.last_observed_at)
          """)
          .param("id", notice.noticeId())
          .param("title", notice.title())
          .param("category", notice.category().name())
          .param("published", notice.publishedAt())
          .param("url", notice.url())
          .param("at", at)
          .update();
  }

  @Override
  public List<PatchNotice> page(PatchNotice.Category category, int offset, int limit) {
    return jdbc.sql(
            """
        SELECT notice_id, title, category, published_at, url FROM patch_notice
        WHERE (CAST(:category AS VARCHAR) IS NULL OR category = :category)
        ORDER BY published_at DESC, notice_id DESC
        LIMIT :limit OFFSET :offset
        """)
        .param("category", category == null ? null : category.name())
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (row, index) ->
                new PatchNotice(
                    row.getLong("notice_id"),
                    row.getString("title"),
                    PatchNotice.Category.valueOf(row.getString("category")),
                    row.getObject("published_at", LocalDateTime.class),
                    row.getString("url")))
        .list();
  }

  @Override
  public long count(PatchNotice.Category category) {
    return jdbc.sql(
            "SELECT COUNT(*) FROM patch_notice WHERE (CAST(:category AS VARCHAR) IS NULL OR category = :category)")
        .param("category", category == null ? null : category.name())
        .query(Long.class)
        .single();
  }

  @Override
  public Coverage coverage() {
    return jdbc.sql("SELECT MIN(published_at), MIN(first_observed_at) FROM patch_notice")
        .query(
            (row, index) -> {
              var first = row.getObject(2, OffsetDateTime.class);
              return new Coverage(
                  row.getObject(1, LocalDateTime.class), first == null ? null : first.toInstant());
            })
        .single();
  }
}
