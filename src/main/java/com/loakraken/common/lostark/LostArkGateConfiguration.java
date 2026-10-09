package com.loakraken.common.lostark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 공유 호출 게이트(#107)와 리더 임대. Valkey 모드는 게이트 전용 연결(명령 타임아웃 100ms)을 따로 둔다. 기본 RedisConnectionFactory 빈을
 * 하나 더 만들면 Boot 자동 구성이 꺼지므로, 연결은 빈이 아닌 {@link GateRedis} 안에서 만든다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LostArkGateProperties.class, ClientLimitProperties.class})
class LostArkGateConfiguration {
  @Bean
  GateRedis lostArkGateRedis(
      LostArkGateProperties gate,
      org.springframework.beans.factory.ObjectProvider<DataRedisConnectionDetails> details) {
    if (!"valkey".equals(gate.store())) return GateRedis.NONE;
    var connection = details.getIfAvailable();
    if (connection == null || connection.getStandalone() == null)
      throw new IllegalStateException("lostark.gate.store=valkey에는 단일 Valkey 연결 정보가 필요합니다.");
    var standalone = connection.getStandalone();
    var config = new RedisStandaloneConfiguration(standalone.getHost(), standalone.getPort());
    config.setDatabase(standalone.getDatabase());
    if (connection.getUsername() != null) config.setUsername(connection.getUsername());
    if (connection.getPassword() != null)
      config.setPassword(RedisPassword.of(connection.getPassword()));
    var factory =
        new LettuceConnectionFactory(
            config,
            LettuceClientConfiguration.builder().commandTimeout(gate.commandTimeout()).build());
    factory.afterPropertiesSet();
    factory.start();
    return new GateRedis(factory);
  }

  @Bean
  GateStore lostArkGateStore(
      GateRedis redis, LostArkGateProperties gate, LostArkProperties lostArk, Clock clock) {
    if (redis.template() == null) return new MemoryGateStore(clock);
    return new ValkeyGateStore(redis.template(), keyId(gate, lostArk));
  }

  @Bean
  ClientLimiter clientLimiter(
      GateRedis redis,
      ClientLimitProperties limits,
      LostArkProperties lostArk,
      Clock clock,
      io.micrometer.core.instrument.MeterRegistry registry) {
    ClientLimitStore store =
        redis.template() == null
            ? new ClientLimitStore.Memory()
            : new ClientLimitStore.Valkey(redis.template());
    return new ClientLimiter(limits, store, clock, registry, lostArk.apiKey());
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<ClientLimitFilter> clientLimitFilter(
      ClientLimiter limiter) {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new ClientLimitFilter(limiter));
    registration.addUrlPatterns("/api/v1/*");
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  @Bean
  LeaderLease lostArkLeaderLease(GateRedis redis) {
    if (redis.template() == null) return new MemoryLeaderLease();
    return new ValkeyLeaderLease(redis.template());
  }

  /** 명시값이 없으면 API 키 SHA-256 앞 12자. 키가 없으면 "none". 키 원문은 쓰지 않는다. */
  static String keyId(LostArkGateProperties gate, LostArkProperties lostArk) {
    if (!gate.keyId().isBlank()) return gate.keyId().strip();
    if (lostArk.apiKey().isBlank()) return "none";
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest(lostArk.apiKey().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 12);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** 게이트 전용 연결. 컨텍스트가 닫힐 때 연결도 닫는다. */
  static final class GateRedis implements org.springframework.beans.factory.DisposableBean {
    static final GateRedis NONE = new GateRedis(null);
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;

    GateRedis(LettuceConnectionFactory factory) {
      this.factory = factory;
      this.template = factory == null ? null : new StringRedisTemplate(factory);
    }

    StringRedisTemplate template() {
      return template;
    }

    @Override
    public void destroy() {
      if (factory != null) factory.destroy();
    }
  }

  /** 단일 인스턴스(memory) 모드에서는 임대를 항상 얻는다. */
  static final class MemoryLeaderLease implements LeaderLease {
    @Override
    public boolean hold(String name, Duration ttl) {
      return true;
    }

    @Override
    public void release(String name) {}
  }
}
