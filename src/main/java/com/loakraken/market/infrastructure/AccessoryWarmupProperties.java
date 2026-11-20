package com.loakraken.market.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 장신구 시세 표 기본 필터 칸 예열(PM 총괄 11-16: 일별 최저가에 빈 날이 생기지 않게). 시세 예열(market.warmup)이 켜진 인스턴스에서만 돌고 예열 전체
 * 분당 상한·예열 게이트 예약분을 함께 쓴다. 1분 틱마다 마지막 관측이 interval보다 오래된 칸 하나를 다시 받는다(칸마다 한 시간에 한 번 꼴).
 *
 * @param enabled 기능 플래그(기본 꺼짐, 다음 배포의 적용 줄로 켬)
 * @param interval 칸 하나를 다시 받는 주기
 */
@ConfigurationProperties("market.warmup.accessories")
public record AccessoryWarmupProperties(
    @DefaultValue("false") boolean enabled, @DefaultValue("60m") Duration interval) {
  public AccessoryWarmupProperties {
    if (interval == null || interval.toMinutes() < 10)
      throw new IllegalArgumentException("장신구 시세 예열 주기는 10분 이상이어야 합니다.");
  }
}
