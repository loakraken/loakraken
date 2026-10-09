package com.loakraken.home.application;

import com.loakraken.home.domain.NewsFeed;
import com.loakraken.home.domain.PatchNotice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 패치 이력 1단계(patch-notice-v1). 소식 조회가 공식 공지를 새로 받을 때(10분 캐시 미스) 패치 공지를 쌓고, 목록을 준다. 공식 호출을 늘리지 않는다.
 */
@Service
public class PatchNoteService implements NewsObserver {
  public static final int MAX_SIZE = 50;
  private static final Logger log = LoggerFactory.getLogger(PatchNoteService.class);
  private final PatchNoticePort notices;

  public PatchNoteService(PatchNoticePort notices) {
    this.notices = notices;
  }

  @Override
  public void observed(HomeCache.NewsSnapshot snapshot) {
    observe(snapshot.news(), snapshot.fetchedAt());
  }

  /** 공식 공지 중 패치 공지만 저장한다. 저장 실패는 소식 응답을 막지 않는다. */
  public void observe(NewsFeed feed, Instant observedAt) {
    var patches = feed.notices().stream().map(PatchNotice::from).flatMap(Optional::stream).toList();
    if (patches.isEmpty()) return;
    try {
      notices.save(patches, observedAt);
    } catch (DataAccessException | TransactionException exception) {
      log.warn("패치 공지를 저장하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  /** 쌓인 목록. 호출하는 쪽이 소식 캐시(10분)를 먼저 거쳐 최신 공지를 반영한다. */
  public Page list(
      PatchNotice.Category category, int page, int size, java.time.Instant sourceFetchedAt) {
    try {
      return new Page(
          notices.page(category, (page - 1) * size, size),
          notices.count(category),
          notices.coverage(),
          sourceFetchedAt);
    } catch (DataAccessException | TransactionException exception) {
      throw new com.loakraken.common.api.BusinessException(HomeErrorCode.HOME_STORAGE_UNAVAILABLE);
    }
  }

  public record Page(
      List<PatchNotice> items,
      long total,
      PatchNoticePort.Coverage coverage,
      Instant sourceFetchedAt) {}
}
