package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * 공통 테스트 저장소(#572)가 실제로 공유 컨테이너의 이 컨텍스트 전용 DB·Valkey 번호에 붙는지 본다. 개발 PC에 같은 기본 주소(localhost:6379)로 뜬
 * Valkey가 있으면 잘못 붙어도 테스트가 통과해 보이므로, 연결 대상을 직접 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TestcontainersConfigurationTest {
  @Autowired TestcontainersConfiguration.Stores stores;
  @Autowired LettuceConnectionFactory redis;
  @Autowired DataSource dataSource;

  @Test
  void 공유_컨테이너의_이_컨텍스트_전용_저장소에_붙는다() throws Exception {
    assertThat(redis.getPort()).isEqualTo(TestcontainersConfiguration.valkeyPort());
    assertThat(redis.getDatabase()).isEqualTo(stores.index());
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getCatalog()).isEqualTo("context_" + stores.index());
    }
  }
}
