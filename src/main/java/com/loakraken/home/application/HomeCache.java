package com.loakraken.home.application;

import com.loakraken.home.domain.GameCalendar;
import com.loakraken.home.domain.NewsFeed;
import java.time.Instant;
import java.util.Optional;

public interface HomeCache {
  record CalendarSnapshot(Instant fetchedAt, GameCalendar calendar) {}

  record NewsSnapshot(Instant fetchedAt, NewsFeed news) {}

  Optional<CalendarSnapshot> findCalendar();

  void saveCalendar(CalendarSnapshot snapshot);

  Optional<NewsSnapshot> findNews();

  void saveNews(NewsSnapshot snapshot);
}
