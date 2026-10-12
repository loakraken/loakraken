package com.loakraken.specup.infrastructure;

import com.loakraken.specup.application.SpecUpGoalService;
import com.loakraken.specup.application.SpecUpGoalStorePort;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 스펙업 목표 경로 캐시 `spec-up:v1:goal:{키}`(L31). 키에 캐릭터 수집 시각·시세 시각·규칙 버전이 들어 있어 바뀌면 새로 계산한다. */
@Component
class ValkeySpecUpGoalStore implements SpecUpGoalStorePort {
  static final String PREFIX = "spec-up:v1:goal:";
  private static final Logger log = LoggerFactory.getLogger(ValkeySpecUpGoalStore.class);
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;

  ValkeySpecUpGoalStore(StringRedisTemplate redis, JsonMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  @Override
  public Optional<SpecUpGoalService.Goal> find(String key) {
    try {
      var json = redis.opsForValue().get(PREFIX + key);
      return json == null
          ? Optional.empty()
          : Optional.ofNullable(mapper.readValue(json, SpecUpGoalService.Goal.class));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void save(String key, SpecUpGoalService.Goal goal, Duration ttl) {
    try {
      redis.opsForValue().set(PREFIX + key, mapper.writeValueAsString(goal), ttl);
    } catch (DataAccessException | JacksonException exception) {
      log.warn("스펙업 목표 경로 캐시를 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }
}
