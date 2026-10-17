package com.loakraken.market.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 보석 매물 직업·스킬 필터 설정(#542). 오픈 첫날 여러 조합 요청이 공유 공식 호출 몫을 먹지 않게 기능 전용 분당 몫을 둔다.
 *
 * @param enabled 끄면 목록·조건 붙은 매물이 GEM_FILTER_DISABLED(공식 호출 0). 조건 없는 매물은 그대로
 * @param quotaPerMinute 조건 붙은 매물 전체(모든 사용자·인스턴스 합)의 분당 공식 호출 상한. Valkey 공유 카운터. 넘으면 캐시만 주고, 캐시가 없으면
 *     MARKET_SEARCH_BUSY + Retry-After
 * @param reserve 공식 호출 게이트에서 사용자 경로 몫으로 남길 호출 수(withUserReserve). 시세판 칸 채우기와 같은 값
 */
@ConfigurationProperties("market.gem-filter")
public record GemFilterPolicy(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("10") int quotaPerMinute,
    @DefaultValue("40") int reserve) {
  public GemFilterPolicy {
    if (quotaPerMinute < 1) throw new IllegalArgumentException("기능 몫은 1 이상이어야 합니다.");
    if (reserve < 0) throw new IllegalArgumentException("사용자 몫은 0 이상이어야 합니다.");
  }
}
