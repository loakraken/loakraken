package com.loakraken.home.application;

import com.loakraken.home.domain.PatchNotice;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public interface PatchNoticePort {
  /** 공지 번호로 중복 제거한다. 제목·분류는 최근 관측으로 바꾼다(내용 추가 표기 등). */
  void save(List<PatchNotice> notices, Instant observedAt);

  /** 게시 시각 내림차순. category가 null이면 전체. */
  List<PatchNotice> page(PatchNotice.Category category, int offset, int limit);

  long count(PatchNotice.Category category);

  /** 저장된 가장 오래된 게시 시각과 처음 관측한 시각. 없으면 null. */
  Coverage coverage();

  record Coverage(LocalDateTime oldestPublishedAt, Instant firstObservedAt) {}
}
