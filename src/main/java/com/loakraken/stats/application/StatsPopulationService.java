package com.loakraken.stats.application;

import com.loakraken.common.api.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 전체 인구 탭(통계 묶음 B5): 가장 최근 주간 스냅샷의 서버별·직업별·레벨별 캐릭터 수. 조건(기간·레벨·전투력)이 없고, 스냅샷이 아직 없으면 날짜 null·빈
 * 목록이다.
 */
@Service
public class StatsPopulationService {
  static final int LEVEL_STEP = 10;
  public static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

  /** 이보다 오래된 스냅샷은 만들 때 지운다. */
  public static final int KEEP_WEEKS = 12;

  /** 다시 만들기가 실패하면 이 인스턴스는 이 시간 동안 다시 시도하지 않고 503을 준다(DB가 아플 때 요청마다 다시 만들지 않게, 코드 리뷰 10-31). */
  static final Duration REBUILD_BACKOFF = Duration.ofSeconds(60);

  private static final Logger log = LoggerFactory.getLogger(StatsPopulationService.class);
  private final PopulationSnapshotPort snapshots;
  private final java.time.Clock clock;
  private final Object rebuild = new Object();
  private volatile Instant rebuildBlockedUntil = Instant.MIN;

  public StatsPopulationService(PopulationSnapshotPort snapshots, java.time.Clock clock) {
    this.snapshots = snapshots;
    this.clock = clock;
  }

  /**
   * server가 null이면 전체, 있으면 그 서버의 직업·레벨 분포(서버 목록은 늘 전체). 저장소 장애면 다른 통계처럼 503 STATS_UNAVAILABLE이다(지난
   * 값을 대신 주지 않음).
   */
  public Population population(String server) {
    try {
      return read(server);
    } catch (DataAccessException | TransactionException exception) {
      log.warn("전체 인구를 읽지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      throw new BusinessException(StatsErrorCode.STATS_UNAVAILABLE);
    }
  }

  private Population read(String server) {
    var latest = current();
    if (latest == null) return new Population(null, server, 0, List.of(), List.of(), List.of());
    var date = latest.date();
    var servers = new ArrayList<Count>();
    var classes = new ArrayList<Count>();
    var levels = new ArrayList<LevelCount>();
    for (var row : snapshots.rows(date, server == null ? "" : server))
      switch (row.dimension()) {
        case "SERVER" -> servers.add(new Count(row.bucket(), row.count()));
        case "CLASS" -> classes.add(new Count(row.bucket(), row.count()));
        case "LEVEL" -> {
          int from = Integer.parseInt(row.bucket());
          levels.add(new LevelCount(from, from + LEVEL_STEP, row.count()));
        }
        default -> {}
      }
    levels.sort(Comparator.comparingInt(LevelCount::from));
    long total = levels.stream().mapToLong(LevelCount::count).sum();
    return new Population(date, server, total, servers, classes, levels);
  }

  /**
   * 가장 최근 스냅샷. 만든 뒤 처리정지 목록이 바뀌었으면 지금 목록으로 오늘(KST) 날짜에 다시 만든 뒤 준다 — 처리정지는 주간 스냅샷을 기다리지 않고 바로
   * 반영한다(다른 통계의 #774와 같은 원칙). 목록은 드물게 바뀌어 다시 만드는 일은 드물고, 한 인스턴스 안에서는 한 요청만 만든다(다른 요청은 기다렸다 새 값을
   * 읽음).
   */
  private PopulationSnapshotPort.Latest current() {
    var latest = snapshots.latest().orElse(null);
    if (latest == null) return null;
    if (latest.exclusionRevision().equals(snapshots.exclusionRevision())) return latest;
    synchronized (rebuild) {
      latest = snapshots.latest().orElse(null);
      if (latest != null && latest.exclusionRevision().equals(snapshots.exclusionRevision()))
        return latest;
      // 직전 다시 만들기가 실패했으면 잠시 시도하지 않는다. 그 사이에도 목록이 바뀐 스냅샷(빠져야 할 캐릭터가 든 값)은 주지 않는다.
      if (clock.instant().isBefore(rebuildBlockedUntil))
        throw new BusinessException(StatsErrorCode.STATS_UNAVAILABLE);
      var today = java.time.LocalDate.now(clock.withZone(KST));
      try {
        snapshots.build(today, today.minusWeeks(KEEP_WEEKS));
        log.info("event=population_snapshot_built trigger=stop_list date={}", today);
      } catch (DataAccessException | TransactionException exception) {
        rebuildBlockedUntil = clock.instant().plus(REBUILD_BACKOFF);
        log.warn(
            "event=population_snapshot_failed trigger=stop_list 처리정지 목록이 바뀐 스냅샷을 다시 만들지 못했습니다. {}초 동안 503. 예외 유형={}",
            REBUILD_BACKOFF.toSeconds(),
            exception.getClass().getSimpleName());
        throw exception;
      }
      return snapshots.latest().orElse(null);
    }
  }

  /** snapshotDate가 null이면 아직 스냅샷이 없다. total = 그 범위(전체 또는 서버)의 캐릭터 수. */
  public record Population(
      LocalDate snapshotDate,
      String server,
      long total,
      List<Count> servers,
      List<Count> classes,
      List<LevelCount> levels) {}

  public record Count(String name, long count) {}

  /** 아이템 레벨 from 이상 to 미만. */
  public record LevelCount(int from, int to, long count) {}
}
