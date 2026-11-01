package com.loakraken.specup.application;

import java.time.Duration;
import java.util.Optional;

/** 스펙업 목표 경로(L31) 결과 캐시. 장애는 계산을 막지 않는다(못 읽으면 다시 계산, 못 쓰면 건너뜀). */
public interface SpecUpGoalStorePort {
  Optional<SpecUpGoalService.Goal> find(String key);

  void save(String key, SpecUpGoalService.Goal goal, Duration ttl);

  /** 추천 순서(이어 고르기) 결과. 기본은 캐시 없음. */
  default Optional<SpecUpGoalService.Path> findPath(String key) {
    return Optional.empty();
  }

  default void savePath(String key, SpecUpGoalService.Path path, Duration ttl) {}
}
