package com.loakraken.character.application;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 캐릭터 검색 화면 조회수(F-8). 검색 API 성공마다 KST 날짜별로 1씩 센다.
 *
 * <p>조회한 사람을 식별하지 않으므로 같은 사람의 반복 조회도 센다. 기록은 응답 경로 밖에서 단일 작업자가 비동기로 쓴다. DB가 느리거나 끊겨도 검색이 지연되지 않게 하기
 * 위해서다. 대기열(QUEUE_CAPACITY)이 차면 그 조회는 세지 않고 지표만 남긴다(근사 집계).
 */
@Service
public class CharacterViewService {
  public static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final Logger log = LoggerFactory.getLogger(CharacterViewService.class);
  private final CharacterViewPort views;
  private final CharacterViewPolicy policy;
  private final CharacterMetrics metrics;
  private final Clock clock;
  static final int QUEUE_CAPACITY = 1000;
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          Thread.ofVirtual().name("character-views-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public CharacterViewService(
      CharacterViewPort views, CharacterViewPolicy policy, CharacterMetrics metrics, Clock clock) {
    this.views = views;
    this.policy = policy;
    this.metrics = metrics;
    this.clock = clock;
  }

  public void record(String characterName) {
    if (!policy.enabled() || characterName == null || characterName.isBlank()) return;
    var date = LocalDate.now(clock.withZone(KST));
    try {
      writer.execute(() -> write(characterName, date));
    } catch (RejectedExecutionException exception) {
      metrics.views(false);
    }
  }

  private void write(String characterName, LocalDate date) {
    try {
      views.increment(characterName, date);
      metrics.views(true);
    } catch (DataAccessException | TransactionException exception) {
      metrics.views(false);
      log.warn("캐릭터 조회수 기록에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    writer.shutdown();
    writer.awaitTermination(5, TimeUnit.SECONDS);
  }

  public record DailyViews(LocalDate date, long count) {}

  public record Views(
      String characterName,
      LocalDate from,
      LocalDate to,
      List<DailyViews> days,
      long rangeTotal,
      long total) {}

  /** 오늘(KST)까지 days일을 0으로 채워 오래된 날부터 돌려준다. */
  public Views views(String characterName, int days) {
    if (!policy.enabled())
      throw new CharacterException(CharacterErrorCode.CHARACTER_VIEWS_DISABLED);
    var to = LocalDate.now(clock.withZone(KST));
    var from = to.minusDays(days - 1L);
    try {
      var counts = views.daily(characterName, from, to);
      var result = new ArrayList<DailyViews>(days);
      long rangeTotal = 0;
      for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
        long count = counts.getOrDefault(date, 0L);
        rangeTotal += count;
        result.add(new DailyViews(date, count));
      }
      return new Views(
          characterName, from, to, List.copyOf(result), rangeTotal, views.total(characterName));
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_VIEWS_UNAVAILABLE);
    }
  }
}
