package com.loakraken.alerts.application;

/** 브라우저 푸시 서비스로 보낸다(M33). 실패 결과·예외 메시지에 주소·키 원문을 넣지 않는다. */
public interface PushSender {
  enum Result {
    /** 2xx. */
    SENT,
    /** 404·410: 구독이 없거나 만료됨. 구독을 지운다. */
    GONE,
    /** 일시 실패(429·5xx·연결·시간 초과). 다음 발송 주기에 한 번 다시 보낸다. */
    RETRY,
    /** 그 밖의 실패(그 밖의 4xx 등). 다시 보내지 않는다(조건은 다음 전환 때). */
    FAILED
  }

  Result send(String endpoint, String p256dh, String auth, byte[] payload);
}
