package com.loakraken.home.application;

import com.loakraken.home.domain.GameCalendar;
import com.loakraken.home.domain.NewsFeed;

/** 공식 GAMECONTENTS·NEWS 조회. 호출마다 공유 예산을 1회씩 쓴다(소식은 공지·이벤트 2회). */
public interface HomeSourcePort {
  GameCalendar fetchCalendar();

  NewsFeed fetchNews();
}
