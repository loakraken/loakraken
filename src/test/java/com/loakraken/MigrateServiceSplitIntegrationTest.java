package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterHistoryPort;
import com.loakraken.character.domain.CharacterProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

/**
 * 일회성 마이그레이션 서비스(F3b): 스키마 소유 역할 비밀번호는 migrate 서비스(와 postgres)에만 붙고, api 실행에는 없다.
 *
 * <ul>
 *   <li>배포 설정(deploy/compose.prod.yaml)을 읽어 api·migrate의 비밀값·환경 변수·의존 순서를 본다.
 *   <li>migrate 명령을 운영과 같은 설정 키(prod 프로필, DB_URL·DB_USERNAME·DB_PASSWORD)로 소유 역할로 돌리고, 역할 SQL로 실행
 *       역할을 맞춘 뒤, api는 실행 역할·Flyway 끔으로 뜬다(소유 역할 자격 증명 없이).
 * </ul>
 */
@Import({TestcontainersConfiguration.class, MigrateServiceSplitIntegrationTest.AppRole.class})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health",
      "management.endpoint.health.probes.enabled=true"
    })
class MigrateServiceSplitIntegrationTest {
  static final Path COMPOSE = Path.of("deploy/compose.prod.yaml");
  static final Path ROLE_SQL = Path.of("deploy/sql/db-app-role.sql");
  static final String OWNER = "loakraken";
  static final String OWNER_PASSWORD = "owner-test-password";
  static final String DATABASE = "loakraken";
  static final String APP = "loakraken_app";
  static final String APP_PASSWORD = "app-test-password";
  static final String SERVER = "분리서버";

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
          .withDatabaseName(DATABASE)
          .withUsername(OWNER)
          .withPassword(OWNER_PASSWORD);

  /** migrate 서비스와 같은 설정 키로 돈 결과(운영 compose의 migrate 환경 변수와 같은 이름). */
  static final int MIGRATE_EXIT;

  /** migrate 뒤 역할 SQL 판정 줄. */
  static final String ROLE_VERDICT;

  static {
    POSTGRES.start();
    MIGRATE_EXIT =
        MigrateCommand.run(
            "--spring.profiles.active=prod",
            "--DB_URL=" + POSTGRES.getJdbcUrl(),
            "--DB_USERNAME=" + OWNER,
            "--DB_PASSWORD=" + OWNER_PASSWORD,
            "--logging.structured.format.console=ecs");
    ROLE_VERDICT = applyRoleSql();
  }

  static String applyRoleSql() {
    try {
      var script =
          "\\set pw '" + APP_PASSWORD + "'\n" + Files.readString(ROLE_SQL, StandardCharsets.UTF_8);
      POSTGRES.copyFileToContainer(Transferable.of(script), "/tmp/db-app-role.sql");
      var result =
          POSTGRES.execInContainer(
              "psql",
              "-X",
              "-q",
              "-At",
              "-v",
              "ON_ERROR_STOP=1",
              "-v",
              "owner=" + OWNER,
              "-v",
              "db=" + DATABASE,
              "-U",
              OWNER,
              "-d",
              DATABASE,
              "-f",
              "/tmp/db-app-role.sql");
      if (result.getExitCode() != 0)
        throw new IllegalStateException("역할 SQL 실패: " + result.getStderr());
      var lines = result.getStdout().strip().lines().toList();
      return lines.isEmpty() ? "" : lines.getLast();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  /** api 실행 연결: 실행 역할만(운영 compose의 api: DB_USERNAME loakraken_app, db_app_password). */
  @TestConfiguration(proxyBeanMethods = false)
  static class AppRole {
    @Bean
    @Primary
    JdbcConnectionDetails appRoleConnectionDetails() {
      return new JdbcConnectionDetails() {
        @Override
        public String getUsername() {
          return APP;
        }

        @Override
        public String getPassword() {
          return APP_PASSWORD;
        }

        @Override
        public String getJdbcUrl() {
          return POSTGRES.getJdbcUrl();
        }
      };
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> service(String name) throws IOException {
    Map<String, Object> compose =
        new Yaml().load(Files.readString(COMPOSE, StandardCharsets.UTF_8));
    var services = (Map<String, Object>) compose.get("services");
    assertThat(services).containsKey(name);
    return (Map<String, Object>) services.get(name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> environment(Map<String, Object> service) {
    return (Map<String, Object>) service.get("environment");
  }

  /** 서비스가 붙이는 비밀값: source → target(짧은 형식이면 target = source). */
  @SuppressWarnings("unchecked")
  private static Map<String, String> secrets(Map<String, Object> service) {
    var result = new java.util.LinkedHashMap<String, String>();
    var list = (List<Object>) service.getOrDefault("secrets", List.of());
    for (var entry : list) {
      if (entry instanceof String name) result.put(name, name);
      else {
        var map = (Map<String, Object>) entry;
        var source = String.valueOf(map.get("source"));
        result.put(source, String.valueOf(map.getOrDefault("target", source)));
      }
    }
    return result;
  }

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;
  @Autowired JdbcClient jdbc;
  @Autowired CharacterHistoryPort history;
  @Autowired Environment environment;
  @Autowired ApplicationContext context;
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(int target, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + target + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void 배포_설정에서_소유_역할_비밀번호는_migrate와_postgres에만_붙고_api에는_없다() throws Exception {
    var api = service("api");
    var migrate = service("migrate");

    // api: 실행 역할로만 접속, Flyway 끔, 소유 역할 비밀번호·Flyway 접속 설정 없음, migrate 성공 뒤에만 뜸.
    assertThat(environment(api))
        .containsEntry("DB_USERNAME", APP)
        .containsEntry("SPRING_FLYWAY_ENABLED", "false");
    assertThat(environment(api).keySet()).noneMatch(key -> key.startsWith("SPRING_FLYWAY_U"));
    assertThat(environment(api).keySet()).noneMatch(key -> key.startsWith("SPRING_FLYWAY_P"));
    assertThat(secrets(api))
        .doesNotContainKey("db_password")
        .containsEntry("db_app_password", "DB_PASSWORD");
    assertThat(secrets(api).values()).noneMatch(target -> target.toLowerCase().contains("flyway"));
    var dependsOn = (Map<String, Object>) api.get("depends_on");
    assertThat((Map<String, Object>) dependsOn.get("migrate"))
        .containsEntry("condition", "service_completed_successfully");

    // migrate: 같은 이미지·같은 설정 경로로 'migrate' 명령, 소유 역할 비밀번호, DB 망만, 한 번 돌고 끝.
    assertThat(migrate.get("image")).isEqualTo(api.get("image"));
    assertThat(migrate.get("command")).isEqualTo(List.of(MigrateCommand.NAME));
    assertThat(migrate.get("restart")).isEqualTo("no");
    assertThat(migrate.get("read_only")).isEqualTo(true);
    assertThat(migrate.get("networks")).isEqualTo(List.of("data"));
    assertThat(secrets(migrate)).containsExactly(Map.entry("db_password", "DB_PASSWORD"));
    assertThat(environment(migrate))
        .containsEntry("SPRING_PROFILES_ACTIVE", environment(api).get("SPRING_PROFILES_ACTIVE"))
        .containsEntry("SPRING_CONFIG_IMPORT", environment(api).get("SPRING_CONFIG_IMPORT"))
        .containsEntry("DB_URL", environment(api).get("DB_URL"));
    assertThat(String.valueOf(environment(migrate).get("DB_USERNAME"))).contains("POSTGRES_USER");

    // 소유 역할 비밀번호(db_password)를 붙이는 서비스는 migrate와 postgres뿐.
    Map<String, Object> compose =
        new Yaml().load(Files.readString(COMPOSE, StandardCharsets.UTF_8));
    var owners =
        ((Map<String, Object>) compose.get("services"))
            .entrySet().stream()
                .filter(e -> secrets((Map<String, Object>) e.getValue()).containsKey("db_password"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    assertThat(owners).containsExactly("migrate", "postgres");
  }

  @Test
  void migrate가_소유_역할로_스키마를_만들고_api는_실행_역할로_Flyway_없이_뜬다() throws Exception {
    // migrate: 운영 설정 키로 성공, 기록은 소유 역할이 했다. 역할 SQL 판정 OK.
    assertThat(MIGRATE_EXIT).isZero();
    assertThat(ROLE_VERDICT).isEqualTo("DB_APP_ROLE_OK");
    var installedBy =
        POSTGRES.execInContainer(
            "psql",
            "-X",
            "-q",
            "-At",
            "-U",
            OWNER,
            "-d",
            DATABASE,
            "-c",
            "SELECT string_agg(DISTINCT installed_by, ',') FROM flyway_schema_history");
    assertThat(installedBy.getStdout().strip()).isEqualTo(OWNER);

    // api: 실행 역할, Flyway 빈 없음, 소유 역할 자격 증명 설정 없음.
    assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(APP);
    assertThat(context.getBeanProvider(Flyway.class).getIfAvailable()).isNull();
    assertThat(environment.containsProperty("spring.flyway.user")).isFalse();
    assertThat(environment.containsProperty("spring.flyway.password")).isFalse();

    // 쓰기·통계·준비 상태.
    history.record(
        new CharacterProfile(
            "분리가",
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null),
        Instant.now());
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM characters WHERE server_name = :s")
                .param("s", SERVER)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(get(port, "/api/v1/stats/summary").statusCode()).isEqualTo(200);
    var readiness = get(managementPort, "/actuator/health/readiness");
    assertThat(readiness.statusCode()).as(readiness.body()).isEqualTo(200);
  }
}
