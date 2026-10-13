package com.loakraken.stats.presentation;

import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 쿼리 activeDays(7·30·90, 없으면 기본 30). 그 밖의 값은 400 INVALID_REQUEST다(조건 생성자 예외가 500이 되지 않게). */
final class ActiveDays {
  private ActiveDays() {}

  static int of(Integer value) {
    if (value == null) return StatsConditions.defaults().activeDays();
    if (!StatsRules.ACTIVE_DAYS.contains(value))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return value;
  }
}
