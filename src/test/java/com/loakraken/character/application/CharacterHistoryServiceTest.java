package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.domain.CharacterProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class CharacterHistoryServiceTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CharacterMetrics metrics = new CharacterMetrics(registry);
  private final CharacterHistoryPort port = mock(CharacterHistoryPort.class);
  private final CharacterProfile profile =
      new CharacterProfile(
          "모험가", "루페온", "바드", 70, new BigDecimal("1700"), null, 300, null, null, null, List.of());
  private final Instant now = Instant.parse("2026-10-05T00:00:00Z");

  @Test
  void 저장_실패는_검색을_막지_않고_실패_지표만_남긴다() {
    doThrow(new DataAccessResourceFailureException("internal-secret"))
        .when(port)
        .record(any(), any());
    var service = new CharacterHistoryService(port, new CharacterHistoryPolicy(true), metrics);
    service.record(profile, now);
    assertThat(
            registry
                .get("loakraken.character.history.writes")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 저장_성공은_성공_지표를_남긴다() {
    var service = new CharacterHistoryService(port, new CharacterHistoryPolicy(true), metrics);
    service.record(profile, now);
    verify(port).record(profile, now);
    assertThat(
            registry
                .get("loakraken.character.history.writes")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 비활성화하면_기록하지_않고_조회는_503이다() {
    var service = new CharacterHistoryService(port, new CharacterHistoryPolicy(false), metrics);
    service.record(profile, now);
    verifyNoInteractions(port);
    assertThatThrownBy(() -> service.latest(new CharacterHistoryQuery("모험가", "루페온", 10)))
        .isInstanceOfSatisfying(
            CharacterException.class,
            exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(CharacterErrorCode.CHARACTER_HISTORY_DISABLED));
  }

  @Test
  void 조회_저장소_장애는_503으로_변환한다() {
    when(port.latest(anyString(), anyString(), anyInt()))
        .thenThrow(new DataAccessResourceFailureException("internal-secret"));
    var service = new CharacterHistoryService(port, new CharacterHistoryPolicy(true), metrics);
    assertThatThrownBy(() -> service.latest(new CharacterHistoryQuery(" 모험가 ", "루페온", 10)))
        .isInstanceOfSatisfying(
            CharacterException.class,
            exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(CharacterErrorCode.CHARACTER_HISTORY_UNAVAILABLE));
  }
}
