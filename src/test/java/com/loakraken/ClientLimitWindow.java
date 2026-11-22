package com.loakraken;

import com.loakraken.common.lostark.ClientLimitClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 통합 테스트의 클라이언트 한도 창 시계. 그 컨텍스트의 앱 시계(보통 실제 시각, 시험이 바꿔 끼운 시계면 그것)에 어긋남(offset)을 더한 시계로, 흐름은 앱 시계와
 * 같다. 앱 시계를 움직여 창을 넘기는 시험은 지금처럼 동작한다. {@link #startFreshWindow()}를 부르면 다음 1분
 * 창이 막 시작한 시각(창 시작 + 1초)으로 옮겨, 그 뒤 59초 동안 보낸 요청은 모두 같은 창에서 센다. 앞 테스트가 같은 주소로 센 카운터도 이전 창에 남는다.
 *
 * <p>한도를 여러 요청에 걸쳐 세는 시험은 시작할 때 이 메서드를 부른다(실제 시계에서는 묶음이 분 경계를 넘으면 카운터가 새로 시작해 시험이 가끔 틀렸다).
 */
public final class ClientLimitWindow {
  private static final long WINDOW_MILLIS = 60_000;
  private static final long LEAD_MILLIS = 1_000;
  private final AtomicLong offset = new AtomicLong();
  private final Clock base;

  ClientLimitWindow(Clock base) {
    this.base = base;
  }

  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public long millis() {
          return base.millis() + offset.get();
        }

        @Override
        public Instant instant() {
          return Instant.ofEpochMilli(millis());
        }
      };

  ClientLimitClock limitClock() {
    return new ClientLimitClock(clock);
  }

  /** 다음 창이 막 시작한 시각으로 옮긴다(시각은 앞으로만 간다). */
  public void startFreshWindow() {
    long now = clock.millis();
    long target = (now / WINDOW_MILLIS + 1) * WINDOW_MILLIS + LEAD_MILLIS;
    offset.addAndGet(target - now);
  }

  /** 지금 창에서 지난 시간(ms). 시험이 창 안에 있는지 확인할 때 쓴다. */
  public long elapsedInWindow() {
    return Math.floorMod(clock.millis(), WINDOW_MILLIS);
  }
}
