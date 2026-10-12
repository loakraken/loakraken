package com.loakraken.market.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * T4 악세 경매 검색기(N13) 설정. 몫 배분은 설계 문서 '분당 공식 호출 몫'을 따른다.
 *
 * @param quotaPerMinute 이 기능 전체(모든 사용자·인스턴스 합)의 분당 공식 호출 상한. Valkey 공유 카운터
 * @param reserve 공식 호출 게이트에서 사용자 경로 몫으로 남길 호출 수(withUserReserve)
 * @param maxScanPages 뒤 필터(힘민지 범위 %) 검색 한 번에 읽는 공식 페이지 상한
 * @param cacheTtl 검색 결과 캐시(빈 결과 포함)
 */
@ConfigurationProperties("market.auction-search")
public record AccessorySearchPolicy(
    @DefaultValue("20") int quotaPerMinute,
    @DefaultValue("30") int reserve,
    @DefaultValue("3") int maxScanPages,
    @DefaultValue("120s") Duration cacheTtl) {
  public AccessorySearchPolicy {
    if (quotaPerMinute < 1) throw new IllegalArgumentException("기능 몫은 1 이상이어야 합니다.");
    if (reserve < 0) throw new IllegalArgumentException("사용자 몫은 0 이상이어야 합니다.");
    if (maxScanPages < 1) throw new IllegalArgumentException("페이지 상한은 1 이상이어야 합니다.");
    if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero())
      throw new IllegalArgumentException("캐시 시간은 양수여야 합니다.");
  }
}
