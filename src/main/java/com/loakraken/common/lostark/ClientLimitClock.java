package com.loakraken.common.lostark;

import java.time.Clock;

/**
 * 클라이언트별 유입 제한({@link ClientLimiter})의 1분 고정 창을 재는 시계. 운영에서는 앱 시계 그대로다. 통합 테스트가 이 빈만 바꿔 끼워, 한도를 세는 요청
 * 묶음이 창 경계를 넘지 않게(경계를 넘으면 카운터가 새로 시작해 한도 시험이 우연히 통과·실패한다) 한다. 앱 시계 빈을 바꾸면 다른 기능의 시각까지 움직이므로 따로
 * 둔다.
 */
public record ClientLimitClock(Clock clock) {}
