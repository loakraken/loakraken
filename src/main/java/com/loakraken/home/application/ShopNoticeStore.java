package com.loakraken.home.application;

import java.util.Collection;
import java.util.Set;

/** 상점 공지 알림이 이미 본 공지(공식 주소)를 기억한다. */
public interface ShopNoticeStore {
  /** 기준을 한 번이라도 저장했는지(처음 켠 날은 알리지 않고 지금 공지를 기준으로만 삼는다). */
  boolean initialized();

  Set<String> seen();

  /** 공지 주소를 본 것으로 기억하고 기준이 있음으로 표시한다. */
  void remember(Collection<String> urls);
}
