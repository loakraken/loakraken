package com.loakraken.character.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;

/**
 * B9 보유 기간 정리(PM 총괄 결정 2026-10-09).
 *
 * <ul>
 *   <li>#141: 마지막 관측 후 1년 동안 다시 관측되지 않은 캐릭터와 그 기록을 지운다. 처리정지 목록은 해제 요청 때까지 남긴다.
 *   <li>#142: 조회수 일별 행(character_view_daily)을 1년 보관한 뒤 지운다.
 * </ul>
 *
 * 한 배치는 {@link #BATCH}행이고 한 번 실행에 {@link #MAX_BATCHES}배치까지만 지운다. 남은 행은 다음 날 이어서 지운다.
 */
@Service
public class CharacterRetentionService {
  public static final Duration RETENTION = Duration.ofDays(365);
  static final int BATCH = 500;
  static final int MAX_BATCHES = 40;
  static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final CharacterRetentionPort retention;
  private final MeterRegistry registry;
  private final Clock clock;
  private final AtomicLong lastSuccess = new AtomicLong();

  public CharacterRetentionService(
      CharacterRetentionPort retention, MeterRegistry registry, Clock clock) {
    this.retention = retention;
    this.registry = registry;
    this.clock = clock;
    Gauge.builder("loakraken.background.last_success", lastSuccess, AtomicLong::doubleValue)
        .tag("job", "retention")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
  }

  public record Result(int characters, int views) {}

  public Result purge() {
    var now = clock.instant();
    int characters =
        drain(
            "characters", () -> retention.deleteUnobservedCharacters(now.minus(RETENTION), BATCH));
    var today = LocalDate.ofInstant(now, KST);
    int views = drain("view_daily", () -> retention.deleteViewsBefore(today.minusDays(365), BATCH));
    lastSuccess.set(now.getEpochSecond());
    return new Result(characters, views);
  }

  private int drain(String target, IntSupplier batch) {
    int total = 0;
    for (int i = 0; i < MAX_BATCHES; i++) {
      int deleted = batch.getAsInt();
      total += deleted;
      if (deleted < BATCH) break;
    }
    registry.counter("loakraken.retention.deleted", "target", target).increment(total);
    return total;
  }
}
