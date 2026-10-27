package com.loakraken.receipt.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 처리정지 목록에 들기 전 입력 지우기: 실패하면 다시 시도하고, 끝내 실패하면 예외 유형만 남긴다(캐릭터 이름 없음). */
@ExtendWith(OutputCaptureExtension.class)
class ValkeyReceiptInputStoreDeleteTest {
  private static final String NAME = "지우기대상캐릭터";
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValkeyReceiptInputStore store =
      new ValkeyReceiptInputStore(
          redis, new JsonMapper(), Duration.ofMinutes(5), Clock.systemUTC());

  @Test
  void 한_번_실패하면_다시_시도해_지운다(CapturedOutput output) {
    when(redis.delete(anyCollection()))
        .thenThrow(new RedisConnectionFailureException("down"))
        .thenReturn(1L);
    store.delete(List.of(NAME));
    verify(redis, times(2)).delete(anyCollection());
    assertThat(output).doesNotContain("receipt_input_delete_failed");
  }

  @Test
  void 끝내_실패하면_정해진_횟수만_시도하고_예외_유형만_남긴다(CapturedOutput output) {
    when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
    store.delete(List.of(NAME));
    verify(redis, times(ValkeyReceiptInputStore.DELETE_ATTEMPTS)).delete(anyCollection());
    assertThat(output)
        .contains("event=receipt_input_delete_failed")
        .contains("RedisConnectionFailureException")
        .doesNotContain(NAME);
  }
}
