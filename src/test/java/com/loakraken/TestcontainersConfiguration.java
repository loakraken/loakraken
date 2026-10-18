package com.loakraken;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 공통 저장소. 컨테이너는 테스트 JVM마다 PostgreSQL·Valkey 하나씩만 띄운다 — 전에는 스프링 컨텍스트마다 새로 떠서 빌드 한 번에 60개 넘게
 * 떴다(#572). 컨텍스트마다 새 데이터베이스와 새 Valkey DB 번호를 연결 설정으로 넘겨, 전처럼 컨텍스트마다 빈 저장소에서 시작한다(컨텍스트끼리 데이터가
 * 섞이지 않음). 컨테이너는 테스트 JVM이 끝날 때 Testcontainers가 정리한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
  /** Valkey DB 번호 수(기본 16). 한 JVM이 만드는 컨텍스트 수(캐시에서 밀려 다시 만드는 것 포함)보다 넉넉하게. */
  private static final int VALKEY_DATABASES = 1024;

  private static final AtomicInteger CONTEXTS = new AtomicInteger();

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

  private static final GenericContainer<?> VALKEY =
      new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.2"))
          .withExposedPorts(6379)
          .withCommand("valkey-server", "--databases", String.valueOf(VALKEY_DATABASES));

  private static synchronized void start() {
    if (!POSTGRES.isRunning()) POSTGRES.start();
    if (!VALKEY.isRunning()) VALKEY.start();
  }

  /** 이 컨텍스트 전용 데이터베이스를 만들고 그 접속 주소를 돌려준다. */
  private static String createDatabase(String name) {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException exception) {
      throw new IllegalStateException("테스트 데이터베이스를 만들지 못했습니다: " + name, exception);
    }
    return POSTGRES
        .getJdbcUrl()
        .replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + name + "$1");
  }

  @Bean
  DynamicPropertyRegistrar storeProperties() {
    start();
    int context = CONTEXTS.incrementAndGet();
    if (context >= VALKEY_DATABASES)
      throw new IllegalStateException("테스트 컨텍스트가 Valkey DB 번호 수보다 많습니다: " + context);
    var url = createDatabase("context_" + context);
    return registry -> {
      registry.add("spring.datasource.url", () -> url);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      registry.add("spring.data.redis.host", VALKEY::getHost);
      registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
      registry.add("spring.data.redis.database", () -> context);
    };
  }
}
