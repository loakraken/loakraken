package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterProfile;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class CharacterHistoryService {
  private static final Logger log = LoggerFactory.getLogger(CharacterHistoryService.class);
  private final CharacterHistoryPort history;
  private final CharacterHistoryPolicy policy;
  private final CharacterMetrics metrics;

  public CharacterHistoryService(
      CharacterHistoryPort history, CharacterHistoryPolicy policy, CharacterMetrics metrics) {
    this.history = history;
    this.policy = policy;
    this.metrics = metrics;
  }

  /** 이력 기록 실패는 검색 결과를 막지 않는다. */
  public void record(CharacterProfile profile, Instant fetchedAt) {
    if (!policy.enabled()) return;
    try {
      history.record(profile, fetchedAt);
      metrics.history(true);
    } catch (DataAccessException | TransactionException exception) {
      metrics.history(false);
      log.warn(
          "캐릭터 관측 이력 저장에 실패했습니다. 검색 결과는 그대로 반환합니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  public List<CharacterObservationPoint> latest(CharacterHistoryQuery query) {
    if (!policy.enabled())
      throw new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_DISABLED);
    try {
      return history.latest(query.characterName(), query.serverName(), query.limit());
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_UNAVAILABLE);
    }
  }
}
