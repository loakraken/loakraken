package com.loakraken.market.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 보석 시세 표 칸 예열(스펙업 보석 비용). 시세 예열(market.warmup)이 켜진 인스턴스에서만 돌고, 예열 전체 분당 상한을 함께 쓴다. 보석 표 20칸(겁화·작열 ×
 * 1~10레벨)을 interval마다 한 바퀴 — 1분마다 ceil(20 ÷ interval 분)칸씩, 오래된 칸부터(PM 총괄 10-25: 10분, 분당 공식 호출 2회).
 *
 * @param enabled 기능 플래그(기본 꺼짐)
 * @param interval 20칸 한 바퀴 주기
 */
@ConfigurationProperties("market.warmup.gems")
public record GemWarmupProperties(
    @DefaultValue("false") boolean enabled, @DefaultValue("10m") Duration interval) {
  public GemWarmupProperties {
    if (interval == null || interval.toMinutes() < 1)
      throw new IllegalArgumentException("보석 시세 예열 주기는 1분 이상이어야 합니다.");
  }

  /** 1분 틱마다 채울 칸 수. */
  public int cellsPerMinute(int cells) {
    return (int) Math.ceil(cells / (double) interval.toMinutes());
  }
}
