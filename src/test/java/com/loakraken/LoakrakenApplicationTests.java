package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LoakrakenApplicationTests {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redisTemplate;

  @Test
  void PostgreSQL과_Valkey에_연결한다() {
    assertThat(jdbcTemplate.queryForObject("select 1", Integer.class)).isEqualTo(1);
    String key = "test:connection";
    try {
      redisTemplate.opsForValue().set(key, "connected", Duration.ofSeconds(30));
      assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("connected");
    } finally {
      redisTemplate.delete(key);
    }
  }
}
