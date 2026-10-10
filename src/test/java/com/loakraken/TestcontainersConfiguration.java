package com.loakraken;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
  }

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> valkeyContainer() {
    return new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.2"))
        .withExposedPorts(6379);
  }
}
