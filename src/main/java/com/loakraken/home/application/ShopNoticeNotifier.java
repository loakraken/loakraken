package com.loakraken.home.application;

/** 운영 알림 채널(Slack)로 글 한 개를 보낸다. 보내지 못하면 예외(부르는 쪽은 기억하지 않고 다음 실행에 다시 보낸다). */
public interface ShopNoticeNotifier {
  void send(String text);
}
