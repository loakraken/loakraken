package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.receipt.application.ReceiptInputStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 영수증 입력 보관 시간(처리방침 '최대 5분')은 공식에서 받은 시각부터 센다. 다시 저장해도 늘어나지 않고, 이미 지난 입력은 저장하지 않는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReceiptInputStoreTtlIntegrationTest {
  private static final String KEY = "receipt:v1:input:영수증보관시간";
  private static final String NAME = "영수증보관시간";
  @Autowired ReceiptInputStore inputs;
  @Autowired StringRedisTemplate redis;

  @BeforeEach
  @AfterEach
  void 정리() {
    redis.delete(KEY);
  }

  @Test
  void 보관_시간은_받은_시각부터_세고_다시_저장해도_늘지_않는다() {
    var observedAt = Instant.now().minus(Duration.ofMinutes(4));
    inputs.save(NAME, input(observedAt));
    var first = redis.getExpire(KEY, TimeUnit.SECONDS);
    // 5분 중 4분이 지났으니 남은 시간은 1분 이하다(5분으로 새로 잡지 않는다).
    assertThat(first).isBetween(1L, 60L);

    // 캐시 적중처럼 같은 입력을 다시 저장해도 남은 시간은 늘지 않는다.
    inputs.save(NAME, input(observedAt));
    assertThat(redis.getExpire(KEY, TimeUnit.SECONDS)).isBetween(1L, first);
  }

  @Test
  void 보관_시간이_이미_지난_입력은_저장하지_않는다() {
    inputs.save(NAME, input(Instant.now().minus(Duration.ofMinutes(6))));
    assertThat(redis.hasKey(KEY)).isFalse();
  }

  @Test
  void 방금_받은_입력은_5분까지_둔다() {
    inputs.save(NAME, input(Instant.now()));
    assertThat(redis.getExpire(KEY, TimeUnit.SECONDS)).isBetween(290L, 300L);
  }

  private static ReceiptInputStore.Input input(Instant observedAt) {
    return new ReceiptInputStore.Input(
        new CharacterLoadout(List.of(), null, null, null, null, null, List.of()), observedAt);
  }
}
