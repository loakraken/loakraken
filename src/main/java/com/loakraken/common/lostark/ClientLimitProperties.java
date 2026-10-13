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
 * @param botPerMinute 검색봇 표시 요청 수(봇 주소별). 봇 표시는 흉내 낼 수 있으므로 generalPerMinute보다 클 수 없다
 * @param rosterRefreshPerMinute 원정대 전투력 불러오기(POST /api/v1/characters/roster-combat-power/refresh)
 *     요청 수(L19)
 * @param auctionSearchPerMinute 악세 경매 검색기(N13)가 일으키는 공식 호출(페이지) 수. 캐시에 맞는 검색은 세지 않는다
 * @param botOfficialPerMinute 모든 검색봇 표시 요청이 함께 일으킬 수 있는 공식 API 호출 수(전체 상한)
 * @param botAddressOfficialPerMinute 검색봇 표시 요청 한 주소가 일으킬 수 있는 공식 API 호출 수. 한 주소가 전체 상한을 혼자 다 쓰지
 *     못하도록 botOfficialPerMinute보다 작아야 한다(사람 클라이언트의 officialPerMinute 대신 쓴다)
 * @param alertSubscribePerMinute 시세 알림 구독 만들기(POST /api/v1/alerts/subscriptions) 요청 수(M33)
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
    @DefaultValue("true") boolean exemptInternal,
    @DefaultValue("2") int rosterRefreshPerMinute,
    @DefaultValue("5") int auctionSearchPerMinute,
    @DefaultValue("20") int botOfficialPerMinute,
    @DefaultValue("5") int botAddressOfficialPerMinute,
    @DefaultValue("5") int alertSubscribePerMinute) {
  public ClientLimitProperties {
    if (enabled && botPerMinute > generalPerMinute)
      throw new IllegalArgumentException("봇 한도는 일반 클라이언트 한도보다 클 수 없습니다.");
    if (enabled && botAddressOfficialPerMinute >= botOfficialPerMinute)
      throw new IllegalArgumentException("봇 주소별 공식 호출 한도는 모든 봇 합계 상한보다 작아야 합니다.");
  }

  public int limit(ClientLimiter.Bucket bucket) {
    return switch (bucket) {
      case OFFICIAL -> officialPerMinute;
      case STATS -> statsPerMinute;
      case GENERAL -> generalPerMinute;
      case BOT -> botPerMinute;
      case ROSTER_REFRESH -> rosterRefreshPerMinute;
      case AUCTION_SEARCH -> auctionSearchPerMinute;
      case BOT_OFFICIAL -> botOfficialPerMinute;
      case BOT_ADDRESS_OFFICIAL -> botAddressOfficialPerMinute;
      case ALERT_SUBSCRIBE -> alertSubscribePerMinute;
    };
  }
}
