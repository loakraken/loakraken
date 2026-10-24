package com.loakraken.character.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 처리정지 목록(#351). 운영자 전용이고 privacy@ 처리정지 요청을 운영자가 직접 반영한다(DB 표 이름은 예전 이름 public_exclusion). 목록의 캐릭터는
 * 검색해도 기록(스냅샷·관측 이력·칭호·카드·장비 요약·조회수·인기)을 남기지 않고 통계·랭킹·인기·수집에서 빠진다. 검색 응답은 공식 실시간 응답 그대로이며, 목록에 있다는
 * 표시는 응답에 넣지 않는다.
 */
@Service
public class CharacterStopList {
  private static final Logger log = LoggerFactory.getLogger(CharacterStopList.class);

  private final CharacterPublicSummaryPort summaries;

  public CharacterStopList(CharacterPublicSummaryPort summaries) {
    this.summaries = summaries;
  }

  /** 목록에 있는가(서버를 비운 항목은 모든 서버). 확인 실패는 예외를 그대로 던진다. */
  public boolean contains(String characterName, String serverName) {
    return summaries.excluded(characterName, serverName);
  }

  /** names 중 그 서버에서 목록에 있는 이름. 확인 실패는 예외를 그대로 던진다. */
  public java.util.Set<String> listed(java.util.Collection<String> names, String serverName) {
    return summaries.excludedNames(names, serverName);
  }

  /** 기록해도 되는가. 확인에 실패하면 기록하지 않는다. */
  public boolean recordable(String characterName, String serverName) {
    try {
      return !contains(characterName, serverName);
    } catch (DataAccessException | TransactionException exception) {
      log.warn("처리정지 목록을 확인하지 못해 기록하지 않습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return false;
    }
  }
}
