package com.loakraken.home.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.home.application.HomeCache;
import com.loakraken.home.application.HomeErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class ValkeyHomeCache implements HomeCache {
  static final String CALENDAR_KEY = "home:v1:calendar";
  static final String NEWS_KEY = "home:v1:news";
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final HomeCacheProperties properties;
  private final Clock clock;

  ValkeyHomeCache(
      StringRedisTemplate redis, JsonMapper mapper, HomeCacheProperties properties, Clock clock) {
    this.redis = redis;
    this.mapper = mapper;
    this.properties = properties;
    this.clock = clock;
  }

  public Optional<CalendarSnapshot> findCalendar() {
    return read(
        CALENDAR_KEY,
        CalendarSnapshot.class,
        CalendarSnapshot::fetchedAt,
        properties.calendarTtl());
  }

  public void saveCalendar(CalendarSnapshot snapshot) {
    write(CALENDAR_KEY, snapshot, properties.calendarTtl());
  }

  public Optional<NewsSnapshot> findNews() {
    return read(NEWS_KEY, NewsSnapshot.class, NewsSnapshot::fetchedAt, properties.newsTtl());
  }

  public void saveNews(NewsSnapshot snapshot) {
    write(NEWS_KEY, snapshot, properties.newsTtl());
  }

  private <T> Optional<T> read(
      String key, Class<T> type, Function<T, Instant> fetchedAt, Duration ttl) {
    try {
      var json = redis.opsForValue().get(key);
      if (json == null) return Optional.empty();
      try {
        var value = mapper.readValue(json, type);
        if (value != null && fresh(fetchedAt.apply(value), ttl)) return Optional.of(value);
      } catch (JacksonException | IllegalArgumentException | NullPointerException ignored) {
        // 손상되거나 이전 형식인 캐시는 지우고 다시 조회한다.
      }
      redis.delete(key);
      return Optional.empty();
    } catch (DataAccessException exception) {
      throw new BusinessException(HomeErrorCode.HOME_CACHE_UNAVAILABLE);
    }
  }

  private void write(String key, Object value, Duration ttl) {
    try {
      redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
    } catch (DataAccessException | JacksonException exception) {
      throw new BusinessException(HomeErrorCode.HOME_CACHE_UNAVAILABLE);
    }
  }

  private boolean fresh(Instant fetchedAt, Duration ttl) {
    var now = clock.instant();
    return fetchedAt != null && !fetchedAt.isAfter(now) && fetchedAt.isAfter(now.minus(ttl));
  }
}
