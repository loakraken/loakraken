package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterPopularService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 인기 검색 시간 버킷 보관 기간 정리(1시간마다). 여러 인스턴스가 같이 돌아도 같은 행을 지울 뿐이다. */
@Component
class CharacterPopularCleanup {
  private static final Logger log = LoggerFactory.getLogger(CharacterPopularCleanup.class);
  private final CharacterPopularService popular;

  CharacterPopularCleanup(CharacterPopularService popular) {
    this.popular = popular;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
  void purge() {
    try {
      popular.purge();
    } catch (DataAccessException exception) {
      log.warn("인기 검색 집계 정리에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }
}
