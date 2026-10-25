package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.TaskScheduler;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 운영 일회성 마이그레이션 명령(F3b): 소유 역할로 Flyway만 돌리고 성공 0·실패 1로 끝난다. 데이터베이스 연결과 Flyway만 뜨고 웹 서버·관리
 * 포트·Valkey· 스케줄러는 없다. 설정은 api와 같은 Spring Boot 설정(명령줄·환경 변수)으로 받는다.
 */
class MigrateCommandIntegrationTest {
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
          .withDatabaseName("loakraken")
          .withUsername("loakraken")
          .withPassword("owner-test-password");

  static {
    POSTGRES.start();
  }

  private static String url(String database) {
    return POSTGRES.getJdbcUrl().replaceFirst("/loakraken(\\?|$)", "/" + database + "$1");
  }

  private static void createDatabase(String database) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    }
  }

  private static String[] args(String database, String... extra) {
    var args =
        new ArrayList<>(
            List.of(
                "--spring.datasource.url=" + url(database),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword()));
    args.addAll(List.of(extra));
    return args.toArray(String[]::new);
  }

  private static long applied(String database) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static long migrationFiles() throws IOException {
    try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
      return files.filter(file -> file.getFileName().toString().matches("V\\d+__.+\\.sql")).count();
    }
  }

  @Test
  void 첫_인자가_migrate일_때만_마이그레이션_명령이다() {
    assertThat(MigrateCommand.requested(new String[] {"migrate"})).isTrue();
    assertThat(MigrateCommand.requested(new String[] {"migrate", "--debug"})).isTrue();
    assertThat(MigrateCommand.requested(new String[] {})).isFalse();
    assertThat(MigrateCommand.requested(new String[] {"--spring.profiles.active=prod"})).isFalse();
  }

  @Test
  void 모든_마이그레이션을_적용하고_다시_돌리면_바뀌는_것_없이_성공한다() throws Exception {
    createDatabase("migrate_ok");
    assertThat(MigrateCommand.run(args("migrate_ok"))).isZero();
    assertThat(applied("migrate_ok")).isEqualTo(migrationFiles());
    assertThat(MigrateCommand.run(args("migrate_ok"))).isZero();
    assertThat(applied("migrate_ok")).isEqualTo(migrationFiles());
  }

  @Test
  void 데이터베이스_연결과_Flyway만_뜨고_웹_Valkey_스케줄러는_없다() throws Exception {
    createDatabase("migrate_beans");
    try (var context = MigrateCommand.start(args("migrate_beans"))) {
      assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
      assertThat(context.getBeanProvider(Flyway.class).getIfAvailable()).isNotNull();
      assertThat(context.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
      assertThat(context.getBeanNamesForType(TaskScheduler.class)).isEmpty();
      assertThat(
              context.containsBean(
                  "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
          .isFalse();
    }
  }

  @Test
  void 마이그레이션이_실패하면_1로_끝난다() throws Exception {
    createDatabase("migrate_broken");
    assertThat(
            MigrateCommand.run(
                args(
                    "migrate_broken",
                    "--spring.flyway.locations=classpath:db/migration,classpath:migrate-broken")))
        .isEqualTo(1);
  }

  @Test
  void Flyway가_꺼져_있으면_조용히_넘기지_않고_1로_끝난다() throws Exception {
    createDatabase("migrate_off");
    assertThat(MigrateCommand.run(args("migrate_off", "--spring.flyway.enabled=false")))
        .isEqualTo(1);
  }
}
