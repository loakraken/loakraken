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
 *
 * <p>처리정지 목록(public_exclusion)이 바뀌면 지난 목록으로 만든 날짜는 그 캐릭터가 세어진 값이라 바로 주지 않고, 다음 만들기가 모두 지운 뒤 오늘을 지금
 * 목록으로 다시 만든다(저장 값은 칭호별 수뿐이라 한 캐릭터 몫만 뺄 수 없음). 그때 날짜 비교 기록은 사라진다(개인정보 우선, PM 결정 11-08).
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

  /** 오늘(KST) 스냅숏이 지금 처리정지 목록으로 이미 있으면 true(만들 필요 없음). */
  public boolean builtToday() {
    var today = LocalDate.now(clock.withZone(KST));
    return snapshots.latest(stats.exclusionRevision()).filter(today::equals).isPresent();
  }

  /** 지난 처리정지 목록으로 만든 날짜가 남아 있으면 true(지우고 오늘을 다시 만들어야 함). */
  public boolean staleStored() {
    return snapshots.hasStale(stats.exclusionRevision());
  }

  /**
   * 오늘(KST) 스냅숏을 만든다(같은 날짜면 바꿔 씀). 지난 처리정지 목록으로 만든 날짜는 같은 트랜잭션에서 지운다. keys = 만든 server key 수(전체
   * 포함), rows = 넣은 칭호 줄 수, resetDays = 목록이 바뀌어 지운 날짜 수.
   */
  public Built build() {
    // 목록 값은 집계보다 먼저 읽는다: 집계 중에 목록이 바뀌면 지난 값이 저장돼 다음 확인에서 다시 만든다(빠져야 할 캐릭터가 지금 값으로 남지 않음).
    var revision = stats.exclusionRevision();
    var now = clock.instant();
    var today = LocalDate.now(clock.withZone(KST));
    var conditions = StatsConditions.defaults();
    var since = conditions.since(now);
    var byServer = new LinkedHashMap<String, StatsPort.TitleEquip>();
    byServer.put(ALL, query.titleEquipNow(null, since, now));
    for (var server : stats.servers(conditions, since))
      if (server.serverName() != null && !server.serverName().isBlank())
        byServer.put(server.serverName(), query.titleEquipNow(server.serverName(), since, now));
    int resetDays = snapshots.replace(today, now, byServer, today.minusDays(KEEP_DAYS), revision);
    int rows = byServer.values().stream().mapToInt(value -> value.titles().size()).sum();
    return new Built(byServer.size(), rows, resetDays);
  }

  /** date·server(null = 전체) 스냅숏. 없으면 404 TITLE_SNAPSHOT_NOT_FOUND. */
  public StatsQueryService.TitleEquipResult on(LocalDate date, String server, String title) {
    var conditions = StatsConditions.defaults().withServer(server);
    var snapshot =
        snapshots
            .find(date, server == null ? ALL : server, stats.exclusionRevision())
            .orElseThrow(() -> new BusinessException(StatsErrorCode.TITLE_SNAPSHOT_NOT_FOUND));
    return query.titleResult(conditions, snapshot.builtAt(), snapshot.data(), title);
  }

  /** 한 번 만든 결과(로그·배포 확인용). resetDays = 처리정지 목록이 바뀌어 지운 날짜 수. */
  public record Built(int keys, int rows, int resetDays) {}

  /** server(null = 전체) 스냅숏 날짜(최신순, 최대 90개). */
  public List<LocalDate> dates(String server) {
    return snapshots.dates(server == null ? ALL : server, MAX_DATES, stats.exclusionRevision());
  }
}
