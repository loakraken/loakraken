package com.loakraken.home.application;

/** 공식 소식을 새로 받았을 때(캐시 미스) 한 번 불린다. 실패는 소식 응답을 막지 않아야 한다. */
public interface NewsObserver {
  void observed(HomeCache.NewsSnapshot snapshot);
}
