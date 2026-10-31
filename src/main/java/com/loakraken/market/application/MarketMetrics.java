package com.loakraken.market.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class MarketMetrics {
  private final MeterRegistry registry;

  public MarketMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void cache(Kind kind, Outcome outcome) {
    registry
        .counter(
            "loakraken.market.cache.operations",
            "kind",
            kind.name().toLowerCase(Locale.ROOT),
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT))
        .increment();
  }

  /**
   * 악세 경매 검색기(N13) 사건: quota_reject(기능 몫), gate_reject(공유 게이트), incomplete(뒤 필터 complete=false).
   */
  public void auctionSearch(String event) {
    registry.counter("loakraken.market.auction_search", "event", event).increment();
  }

  /**
   * 보석 매물 직업·스킬 필터(#542) 사건: cache_hit(1분 캐시), joined(같은 조건 동시 요청에 합류), official_call(공식 검색 1회),
   * quota_reject(기능 전용 분당 몫), gate_reject(공유 게이트), wait_timeout(합류 대기 15초 초과). 값은 이 여섯 개뿐이다.
   */
  public void gemFilter(String event) {
    registry.counter("loakraken.market.gem_filter", "event", event).increment();
  }

  /**
   * 스펙업 연마 시세(#741) 사건: cache_hit(캐시된 칸), filled(공식 검색 1회로 채움), fill_off(채우기 스위치 꺼짐), lock_busy(다른
   * 채우기가 잠금을 잡음), no_budget(이 요청의 채우기 몫을 다 씀), official_failed(공식 호출 한도·장애), unsupported(경매장 옵션으로
   * 나타낼 수 없는 줄). 값은 이 일곱 개뿐이다.
   */
  public void polishFill(String event) {
    registry.counter("loakraken.market.polish_fill", "event", event).increment();
  }

  public enum Kind {
    PRICES,
    OPTIONS,
    ITEM_HISTORY,
    BOARD_SCAN,
    RELIC_BOOKS,
    REFINING_MATERIALS,
    LIFE_MATERIALS,
    AUCTION_SEARCH
  }

  public enum Outcome {
    HIT,
    MISS,
    INVALID,
    READ_ERROR,
    WRITE_SUCCESS,
    WRITE_ERROR
  }
}
