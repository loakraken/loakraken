package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.character.application.CharacterHistoryPort;
import com.loakraken.character.application.CharacterRetentionService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 운영 DB 역할 분리: Flyway는 소유 역할로, api 실행 연결은 행 읽기·쓰기만 되는 실행 역할(loakraken_app)로. 실행 역할은 배포 설정의 역할 SQL
 * (deploy/sql/db-app-role.sql)을 운영과 같은 psql로 돌려 만든다 — 마이그레이션 전(새 표는 기본 권한으로)과 뒤(기존 표, 판정 줄)에 한 번씩.
 * 그 역할로 앱이 뜨고(스케줄러 포함), 쓰기·통계·정리 작업·준비 상태가 되는지, 스키마 변경과 Flyway 기록 변경은 막히는지 본다.
 */
@Import({TestcontainersConfiguration.class, DbRoleSplitIntegrationTest.SplitRoles.class})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health",
      "management.endpoint.health.probes.enabled=true"
    })
class DbRoleSplitIntegrationTest {
  static final String OWNER = "loakraken";
  static final String OWNER_PASSWORD = "owner-test-password";
  static final String DATABASE = "loakraken";
  static final String APP = "loakraken_app";
  static final String APP_PASSWORD = "app-test-password";
  static final Path ROLE_SQL = Path.of("deploy/sql/db-app-role.sql");
  static final String SERVER = "역할서버";

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
          .withDatabaseName(DATABASE)
          .withUsername(OWNER)
          .withPassword(OWNER_PASSWORD);

  /** 마이그레이션 전 첫 실행(표가 없어 판정은 아직 CHECK)의 출력. 실행 자체가 실패하면 컨텍스트 전에 멈춘다. */
  static final String BEFORE_MIGRATION;

  static {
    POSTGRES.start();
    BEFORE_MIGRATION = applyRoleSql();
  }

  /** 운영 A단계와 같이 비밀번호는 psql 변수로만 넘기고, 소유 역할로 역할 SQL을 돌린다. 마지막 줄이 판정. */
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

  /** 실행 연결은 실행 역할, Flyway는 소유 역할(운영 compose의 DB_USERNAME·spring.flyway.user와 같은 나눔). */
  @TestConfiguration(proxyBeanMethods = false)
  static class SplitRoles {
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

    @Bean
    DynamicPropertyRegistrar flywayOwner() {
      return registry -> {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
      };
    }
  }

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;
  @Autowired JdbcClient jdbc;
  @Autowired CharacterHistoryPort history;
  @Autowired CharacterRetentionService retention;
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(int target, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + target + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void 마이그레이션_뒤_역할_SQL을_다시_돌리면_모든_표에_권한이_있고_판정이_OK다() throws Exception {
    assertThat(BEFORE_MIGRATION).isIn("DB_APP_ROLE_OK", "DB_APP_ROLE_CHECK");
    assertThat(applyRoleSql()).isEqualTo("DB_APP_ROLE_OK");
    // 마이그레이션은 소유 역할이 했다(Flyway 기록은 실행 역할이 읽지도 못하므로 소유 역할로 확인).
    assertThat(
            ownerQuery("SELECT string_agg(DISTINCT installed_by, ',') FROM flyway_schema_history"))
        .isEqualTo(OWNER);
  }

  private static String ownerQuery(String sql) throws Exception {
    var result =
        POSTGRES.execInContainer("psql", "-X", "-q", "-At", "-U", OWNER, "-d", DATABASE, "-c", sql);
    return result.getStdout().strip();
  }

  @Test
  void 실행_역할로_쓰기_통계_정리_준비상태가_되고_스키마_변경은_막힌다() throws Exception {
    // 운영 순서처럼 기존 표가 있는 상태에서 역할 SQL을 돌린 뒤(Flyway 기록 권한 회수 포함).
    assertThat(applyRoleSql()).isEqualTo("DB_APP_ROLE_OK");
    // 실행 연결은 실행 역할이고 역할 한도가 걸려 있다.
    assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(APP);
    assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("15s");
    assertThat(jdbc.sql("SHOW idle_in_transaction_session_timeout").query(String.class).single())
        .isEqualTo("1min");

    // 쓰기 경로: 프로필 관측 기록(캐릭터·관측 이력, 시퀀스·충돌 갱신·잠금 포함).
    history.record(
        new CharacterProfile(
            "역할가",
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

    // 통계(읽기 전용 트랜잭션)와 정리 작업(트랜잭션 안 SET LOCAL statement_timeout, 하위 표까지 지우기).
    assertThat(get(port, "/api/v1/stats/summary").statusCode()).isEqualTo(200);
    var purged = retention.purge();
    assertThat(purged.characters()).isGreaterThanOrEqualTo(0);

    // 준비 상태 UP(데이터베이스 포함).
    var readiness = get(managementPort, "/actuator/health/readiness");
    assertThat(readiness.statusCode()).as(readiness.body()).isEqualTo(200);
    var health = get(managementPort, "/actuator/health");
    assertThat(health.statusCode()).as(health.body()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");

    // 스키마 변경·Flyway 기록 변경·서버 파일 읽기는 막힌다.
    assertThatThrownBy(() -> jdbc.sql("CREATE TABLE role_check (id INT)").update())
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.sql("ALTER TABLE characters ADD COLUMN role_check INT").update())
        .rootCause()
        .hasMessageContaining("must be owner");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM flyway_schema_history").update())
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () -> jdbc.sql("SELECT pg_read_file('/etc/hostname')").query(String.class).single())
        .rootCause()
        .hasMessageContaining("permission denied");
  }
}
