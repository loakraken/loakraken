package com.loakraken.stats.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.stats.domain.StatsConditions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 칭호 장착 확인 일별 스냅숏(칭호 통계 날짜 비교). 하루 한 번 30일 창으로 전체('')와 서버마다 지금 결과를 저장하고, 날짜를 주면 그 저장 값을 지금과 같은 모양으로
 * 준다. 우리 표만 읽는다(공식 호출 없음). 365일보다 오래된 날짜는 만들 때 지운다.
 */
@Service
public class TitleSnapshotService {
  public static final ZoneId KST = ZoneId.of("Asia/Seoul");
  public static final int KEEP_DAYS = 365;
  public static final int MAX_DATES = 90;
  static final String ALL = "";

  private final TitleSnapshotPort snapshots;
  private final StatsPort stats;
  private final StatsQueryService query;
  private final Clock clock;

  public TitleSnapshotService(
      TitleSnapshotPort snapshots, StatsPort stats, StatsQueryService query, Clock clock) {
    this.snapshots = snapshots;
    this.stats = stats;
    this.query = query;
    this.clock = clock;
  }

  /** 오늘(KST) 스냅숏이 이미 있으면 true(만들 필요 없음). */
  public boolean builtToday() {
    var today = LocalDate.now(clock.withZone(KST));
    return snapshots.latest().filter(today::equals).isPresent();
  }

  /** 오늘(KST) 스냅숏을 만든다(같은 날짜면 바꿔 씀). 만든 server key 수(전체 포함). */
  public int build() {
    var now = clock.instant();
    var today = LocalDate.now(clock.withZone(KST));
    var conditions = StatsConditions.defaults();
    var since = conditions.since(now);
    var byServer = new LinkedHashMap<String, StatsPort.TitleEquip>();
    byServer.put(ALL, query.titleEquipNow(null, since, now));
    for (var server : stats.servers(conditions, since))
      if (server.serverName() != null && !server.serverName().isBlank())
        byServer.put(server.serverName(), query.titleEquipNow(server.serverName(), since, now));
    snapshots.replace(today, now, byServer, today.minusDays(KEEP_DAYS));
    return byServer.size();
  }

  /** date·server(null = 전체) 스냅숏. 없으면 404 TITLE_SNAPSHOT_NOT_FOUND. */
  public StatsQueryService.TitleEquipResult on(LocalDate date, String server, String title) {
    var conditions = StatsConditions.defaults().withServer(server);
    var snapshot =
        snapshots
            .find(date, server == null ? ALL : server)
            .orElseThrow(() -> new BusinessException(StatsErrorCode.TITLE_SNAPSHOT_NOT_FOUND));
    return query.titleResult(conditions, snapshot.builtAt(), snapshot.data(), title);
  }

  /** server(null = 전체) 스냅숏 날짜(최신순, 최대 90개). */
  public List<LocalDate> dates(String server) {
    return snapshots.dates(server == null ? ALL : server, MAX_DATES);
  }
}
