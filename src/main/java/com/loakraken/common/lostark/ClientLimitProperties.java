package com.loakraken.common.lostark;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 클라이언트별 유입 제한(#108). 모든 값은 분당 횟수이고, 1분 고정 창으로 센다.
 *
 * @param enabled 끄면 어떤 버킷도 세지 않는다
 * @param officialPerMinute 한 클라이언트 요청이 실제로 일으킨 공식 API 호출 수. 캐시 적중·단일 비행 합류는 세지 않는다
 * @param statsPerMinute /stats/**·/rankings·/characters/rank 요청 수(DB 집계)
 * @param generalPerMinute /api/v1/** 전체 요청 수
 * @param botPerMinute 검색봇 표시 요청 전체(모든 봇 주소 합산). 봇 표시는 흉내 낼 수 있으므로 generalPerMinute보다 클 수 없다
 * @param exemptInternal 원격 주소가 루프백·사설망이면 세지 않는다. 운영에서는 웹이 실제 IP를 X-Forwarded-For로 넘기므로, 사설 주소는 헤더가
 *     빠진 내부 요청이다
 */
@ConfigurationProperties("lostark.client-limit")
public record ClientLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("20") int officialPerMinute,
    @DefaultValue("60") int statsPerMinute,
    @DefaultValue("240") int generalPerMinute,
    @DefaultValue("120") int botPerMinute,
    @DefaultValue("true") boolean exemptInternal) {
  public ClientLimitProperties {
    if (enabled && botPerMinute > generalPerMinute)
      throw new IllegalArgumentException("봇 한도는 일반 클라이언트 한도보다 클 수 없습니다.");
  }

  public int limit(ClientLimiter.Bucket bucket) {
    return switch (bucket) {
      case OFFICIAL -> officialPerMinute;
      case STATS -> statsPerMinute;
      case GENERAL -> generalPerMinute;
      case BOT -> botPerMinute;
    };
  }
}
