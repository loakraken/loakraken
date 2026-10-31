package com.loakraken.stats.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 전체 인구 탭(통계 묶음 B5): 가장 최근 주간 스냅샷의 서버별·직업별·레벨별 캐릭터 수. 조건(기간·레벨·전투력)이 없고, 스냅샷이 아직 없으면 날짜 null·빈
 * 목록이다.
 */
@Service
public class StatsPopulationService {
  static final int LEVEL_STEP = 10;
  private final PopulationSnapshotPort snapshots;

  public StatsPopulationService(PopulationSnapshotPort snapshots) {
    this.snapshots = snapshots;
  }

  /** server가 null이면 전체, 있으면 그 서버의 직업·레벨 분포(서버 목록은 늘 전체). */
  public Population population(String server) {
    var date = snapshots.latest().orElse(null);
    if (date == null) return new Population(null, server, 0, List.of(), List.of(), List.of());
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
