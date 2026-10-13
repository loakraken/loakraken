package com.loakraken.market.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 거래소 분 단위 차트(K35). 예열이 받은 가격(이벤트)을 관측 시각의 UTC 분 칸으로 저장하고, 최근 N분의 점을 돌려준다. 관측하지 않은 분은 채우지 않는다 (추정
 * 금지). 공식 API를 부르지 않는다.
 */
@Service
public class MarketMinuteService {
  public static final int MAX_MINUTES = 120;
  public static final int DEFAULT_MINUTES = 60;
  public static final Duration RETENTION = Duration.ofHours(2);
  private static final Duration CACHE_TTL = Duration.ofSeconds(30);

  private final MarketMinuteStore store;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public MarketMinuteService(MarketMinuteStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * @param itemName 관측한 적이 없으면 null
   * @param sampleEverySeconds 마지막 점의 예열 주기(초). 점이 없으면 null
   */
  public record MinuteChart(
      long itemId,
      String itemName,
      int intervalSeconds,
      Integer sampleEverySeconds,
      Instant from,
      Instant to,
      List<MarketMinuteStore.Point> points) {}

  private record Cached(Instant until, MinuteChart chart) {}

  /** 예열 이벤트 하나를 분 칸으로 저장한다. 같은 분에 두 번이면 나중 값이다. */
  public void record(MarketPricesObserved event) {
    var minute = event.observedAt().truncatedTo(ChronoUnit.MINUTES);
    int sampleEvery = (int) Math.max(1, event.sampleEvery().toSeconds());
    var points = new ArrayList<MarketMinuteStore.Point>();
    for (var price : event.prices())
      points.add(
          new MarketMinuteStore.Point(
              price.id(),
              minute,
              price.name(),
              price.currentMinPrice(),
              price.recentPrice(),
              sampleEvery));
    if (!points.isEmpty()) store.save(points);
  }

  /** 최근 minutes분(1~120) 동안 관측한 분의 점. 관측한 적 없는 품목은 빈 목록이다. */
  public MinuteChart chart(long itemId, int minutes) {
    var now = clock.instant();
    var key = itemId + ":" + minutes;
    var hit = cache.get(key);
    if (hit != null && now.isBefore(hit.until())) return hit.chart();
    var to = now.truncatedTo(ChronoUnit.MINUTES);
    // 지금 진행 중인 분까지 넣어 minutes개 칸(from 포함 ~ to 포함).
    var from = to.minus(Duration.ofMinutes(minutes - 1L));
    var points = store.find(itemId, from);
    var last = points.isEmpty() ? null : points.getLast();
    var chart =
        new MinuteChart(
            itemId,
            last == null ? null : last.itemName(),
            60,
            last == null ? null : last.sampleEverySeconds(),
            from,
            to,
            List.copyOf(points));
    if (cache.size() > 10_000) cache.clear();
    cache.put(key, new Cached(now.plus(CACHE_TTL), chart));
    return chart;
  }

  /** 보관 기간(2시간)보다 오래된 분을 지운다. */
  public int cleanup() {
    return store.deleteBefore(clock.instant().minus(RETENTION));
  }
}
