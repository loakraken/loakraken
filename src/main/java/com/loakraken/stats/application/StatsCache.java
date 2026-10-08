package com.loakraken.stats.application;

import java.util.Optional;

/** 집계 결과 캐시(10분). 캐시 장애는 조회를 막지 않는다: 읽기 실패는 미적중, 쓰기 실패는 무시한다. */
public interface StatsCache {
  <T> Optional<T> find(String key, Class<T> type);

  void save(String key, Object value);
}
