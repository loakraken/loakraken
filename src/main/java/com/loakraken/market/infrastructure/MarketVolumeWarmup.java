package com.loakraken.market.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRateLimitedException;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.application.MarketCache;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.application.MarketQueryService;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import com.loakraken.market.domain.MarketVolume;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시세 거래량 채우기(거래량 정렬). 하루(KST)에 한 바퀴, 설정한 탭마다 기본 티어 전체 스캔(보드와 같은 캐시)의 품목을 골라 공식 상품 상세 이력을 받고 완료일
 * 거래량을 그날 거래량 지도(Valkey, TTL 36시간)에 둔다. 완료일 값이라 하루에 한 번이면 된다.
 *
 * <p>기본은 꺼짐. 켜진 인스턴스끼리 리더 임대로 한 곳만 돈다. 공식 호출은 사용자 몫(reserve)을 남기도록 관문에서 판정하고, 이 작업만의 분당 상한(기본 15)과
 * 하루 상한(기본 800, Valkey 카운터)을 따로 지킨다. 탭 스캔은 캐시에 없을 때만 부르고 10번으로 미리 센다. 이미 그날 지도에 있거나 오늘 받은 이력 캐시로
 * 계산할 수 있는 품목은 공식 호출 없이 채운다. 공식 한도·관문 장애를 만나면 이번 주기를 멈추고 다음 주기에 이어 받는다.
 */
@Component
@ConditionalOnProperty(prefix = "market.volume-warmup", name = "enabled", havingValue = "true")
class MarketVolumeWarmup {
  private static final Logger log = LoggerFactory.getLogger(MarketVolumeWarmup.class);
  static final String LEASE = "market-volume";
  static final int RESERVE = 40;
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final MarketBoardService boards;
  private final MarketQueryService markets;
  private final MarketCache cache;
  private final MarketMetrics metrics;
  private final MarketVolumeWarmupProperties properties;
  private final LostArkProperties lostArk;
  private final LostArkRequestGate gate;
  private final LeaderLease lease;
  private final Clock clock;
  private final AtomicLong lastSuccess = new AtomicLong();
  private final AtomicLong pending = new AtomicLong();

  // 리더 한 곳에서만 돈다. 날짜가 바뀌면 처음부터.
  private LocalDate day;
  private final ArrayDeque<MarketTab> tabs = new ArrayDeque<>();
  private final ArrayDeque<MarketPrice> queue = new ArrayDeque<>();
  private final Set<Long> seen = new HashSet<>();
  private int filledToday;
  private boolean doneLogged;
  private boolean dailyCapLogged;
  private long callWindow = -1;
  private int callsInWindow;

  MarketVolumeWarmup(
      MarketBoardService boards,
      MarketQueryService markets,
      MarketCache cache,
      MarketMetrics metrics,
      MarketVolumeWarmupProperties properties,
      LostArkProperties lostArk,
      LostArkRequestGate gate,
      LeaderLease lease,
      Clock clock,
      MeterRegistry registry) {
    this.boards = boards;
    this.markets = markets;
    this.cache = cache;
    this.metrics = metrics;
    this.properties = properties;
    this.lostArk = lostArk;
    this.gate = gate;
    this.lease = lease;
    this.clock = clock;
    Gauge.builder("loakraken.background.last_success", lastSuccess, AtomicLong::doubleValue)
        .tag("job", "warmup_volume")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
    Gauge.builder("loakraken.market.volume_warmup.pending", pending, AtomicLong::doubleValue)
        .description("오늘 아직 거래량을 받지 못한 대기 품목 수(스캔하지 않은 탭은 빠짐)")
        .register(registry);
  }

  @Scheduled(
      fixedDelayString = "${market.volume-warmup.interval:60s}",
      initialDelayString = "${market.volume-warmup.initial-delay:2m}")
  void warm() {
    MDC.put("entryPoint", "volume_warmup");
    try {
      tick();
    } finally {
      MDC.remove("entryPoint");
    }
  }

  synchronized void tick() {
    if (lostArk.apiKey().isBlank()) return;
    if (!lease.hold(LEASE, properties.interval().plusMinutes(2))) return;
    LocalDate today = LocalDate.now(clock.withZone(KST));
    if (!today.equals(day)) startDay(today);
    while (true) {
      if (queue.isEmpty()) {
        if (tabs.isEmpty()) {
          finishDay(today);
          return;
        }
        if (!scanNextTab(today)) return;
        continue;
      }
      if (!fillNext(today)) return;
    }
  }

  private void startDay(LocalDate today) {
    day = today;
    tabs.clear();
    tabs.addAll(properties.tabs());
    queue.clear();
    seen.clear();
    filledToday = 0;
    doneLogged = false;
    dailyCapLogged = false;
    pending.set(0);
  }

  private void finishDay(LocalDate today) {
    pending.set(0);
    if (doneLogged) return;
    doneLogged = true;
    lastSuccess.set(clock.instant().getEpochSecond());
    log.info("event=volume_warmup_done day={} filled={} items={}", today, filledToday, seen.size());
  }

  /** 다음 탭의 스캔 품목을 대기열에 넣는다. 멈춰야 하면 false. */
  private boolean scanNextTab(LocalDate today) {
    var tab = tabs.peekFirst();
    boolean cached = cache.findBoardScan(tab, tab.defaultTier()).isPresent();
    if (!cached && !reserve(today, MarketBoardService.MAX_SCAN_PAGES)) return false;
    try {
      var scan = gate.withReserve(RESERVE, () -> boards.scanForVolume(tab));
      tabs.pollFirst();
      enqueue(scan.items(), today);
      return true;
    } catch (BusinessException exception) {
      metrics.volumeWarmup(failure("tab=" + tab, exception));
      return false;
    } catch (RuntimeException exception) {
      metrics.volumeWarmup("official_failed");
      log.warn(
          "event=volume_warmup_failed tab={} exceptionType={}",
          tab,
          exception.getClass().getName());
      tabs.pollFirst();
      return true;
    }
  }

  /** 이미 그날 지도에 있는 품목은 빼고, 오늘 받은 이력 캐시로 계산되는 품목은 공식 호출 없이 채운 뒤 나머지를 대기열에 넣는다. */
  private void enqueue(java.util.List<MarketPrice> items, LocalDate today) {
    var fresh = new ArrayList<MarketPrice>();
    for (var item : items) if (seen.add(item.id())) fresh.add(item);
    if (fresh.isEmpty()) return;
    var ids = fresh.stream().map(MarketPrice::id).toList();
    var known = cache.findVolumes(today, ids);
    var histories = cache.findItemHistories(ids);
    var computed = new ArrayList<MarketVolume>();
    for (var item : fresh) {
      if (known.containsKey(item.id())) continue;
      var history = histories.get(item.id());
      if (history != null && LocalDate.ofInstant(history.observedAt(), KST).equals(today)) {
        var volume = MarketBoardService.volumeOf(history.history(), item, today);
        if (volume != null) {
          computed.add(volume);
          continue;
        }
      }
      queue.addLast(item);
    }
    if (!computed.isEmpty()) {
      cache.saveVolumes(today, computed);
      filledToday += computed.size();
    }
    pending.set(queue.size());
  }

  /** 대기열 맨 앞 품목의 상세 이력을 받아 거래량을 채운다. 멈춰야 하면 false. */
  private boolean fillNext(LocalDate today) {
    var item = queue.peekFirst();
    if (!reserve(today, 1)) return false;
    try {
      var result = gate.withReserve(RESERVE, () -> markets.refreshItemHistory(item.id()));
      queue.pollFirst();
      pending.set(queue.size());
      var volume =
          result
              .map(
                  value ->
                      MarketBoardService.volumeOf(
                          value.history(), item, LocalDate.ofInstant(value.observedAt(), KST)))
              .orElse(null);
      if (volume == null) {
        metrics.volumeWarmup("not_found");
        return true;
      }
      cache.saveVolumes(today, java.util.List.of(volume));
      filledToday++;
      lastSuccess.set(clock.instant().getEpochSecond());
      metrics.volumeWarmup("filled");
      return true;
    } catch (BusinessException exception) {
      metrics.volumeWarmup(failure("itemId=" + item.id(), exception));
      if (exception.errorCode() == LostArkErrorCode.LOST_ARK_RATE_LIMITED
          || exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE) return false;
      // 그 품목만의 실패면 건너뛰고 계속한다(다음 날 다시).
      queue.pollFirst();
      pending.set(queue.size());
      return true;
    } catch (RuntimeException exception) {
      metrics.volumeWarmup("official_failed");
      log.warn(
          "event=volume_warmup_failed itemId={} exceptionType={}",
          item.id(),
          exception.getClass().getName());
      queue.pollFirst();
      pending.set(queue.size());
      return true;
    }
  }

  /** 분당 상한(인스턴스 안)과 하루 상한(Valkey)을 calls만큼 잡는다. 분당을 먼저 보고, 하루 카운터는 분당을 통과할 때만 늘린다. */
  boolean reserve(LocalDate today, int calls) {
    long window = clock.millis() / 60_000;
    if (window != callWindow) {
      callWindow = window;
      callsInWindow = 0;
    }
    if (callsInWindow + calls > properties.maxCallsPerMinute()) {
      metrics.volumeWarmup("minute_cap");
      return false;
    }
    long total = cache.addVolumeCalls(today, calls);
    if (total > properties.maxCallsPerDay()) {
      metrics.volumeWarmup("daily_cap");
      if (!dailyCapLogged) {
        dailyCapLogged = true;
        log.info(
            "event=volume_warmup_daily_cap day={} filled={} pending={} tabsLeft={}",
            today,
            filledToday,
            queue.size(),
            tabs.size());
      }
      return false;
    }
    callsInWindow += calls;
    return true;
  }

  /**
   * 관문이 사용자 몫을 남기려고·공식 한도 대기로 미룬 것은 gate_reject(INFO), 공식 429와 그 밖의 오류는 official_failed(WARN). 둘 다
   * 다음 주기·다음 날에 다시 받는다.
   */
  private static String failure(String detail, BusinessException exception) {
    var reason = LostArkRateLimitedException.reasonOf(exception);
    boolean deferred =
        exception instanceof LostArkRateLimitedException limited
            && limited.limitCause() != LostArkRateLimitedException.Cause.UPSTREAM_429;
    if (deferred) {
      log.info("event=volume_warmup_deferred {} reason={}", detail, reason);
      return "gate_reject";
    }
    log.warn(
        "event=volume_warmup_failed {} code={} reason={}",
        detail,
        exception.errorCode().code(),
        reason);
    return "official_failed";
  }
}
