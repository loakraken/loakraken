package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkRequestGate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 운영 Valkey 접근 제어(기본 사용자 끔, 앱 사용자는 위험 명령 없이)로도 api가 쓰는 명령이 모두 되는지 본다. 공식 호출 게이트·클라이언트 한도·통계 캐시· 대표
 * 실행 임대(Lua 포함), 집합 명령, 준비 상태(health)를 앱 사용자로 돌리고, 그동안 서버의 거부 기록(ACL LOG)이 비어 있는지 본다(연결 때 클라이언트
 * 라이브러리가 조용히 넘기는 거부까지 잡음). 앱이 쓰는 명령 하나하나는 ACL DRYRUN으로도 확인한다. 운영 Valkey 설정과 같은 규칙이라, api에 새 명령이 들어가
 * 규칙에 막히면 여기서 실패한다.
 */
@Import({TestcontainersConfiguration.class, ValkeyAclIntegrationTest.AclRedis.class})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "lostark.gate.store=valkey",
      "spring.data.redis.username=" + ValkeyAclIntegrationTest.USER,
      "spring.data.redis.password=" + ValkeyAclIntegrationTest.PASSWORD,
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health",
      "management.endpoint.health.probes.enabled=true"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ValkeyAclIntegrationTest {
  static final String USER = "app";
  static final String PASSWORD = "acl-test-password";
  // 테스트 확인 전용 관리 사용자(ACL LOG·DRYRUN 읽기). 앱 사용자 규칙과는 무관하다.
  static final String INSPECTOR = "acl-inspector";
  static final String INSPECTOR_PASSWORD = "acl-inspector-password";

  /** 운영과 같은 접근 규칙(배포 설정과 맞춤): 기본 사용자 끔, 앱 사용자는 모든 키·채널에 위험 명령만 빼고 INFO는 허용. */
  static final GenericContainer<?> VALKEY =
      new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.2"))
          .withExposedPorts(6379)
          .withCommand(
              "valkey-server",
              "--user",
              "default",
              "off",
              "--user",
              USER,
              "on",
              ">" + PASSWORD,
              "~*",
              "&*",
              "+@all",
              "-@dangerous",
              "+info",
              "--user",
              INSPECTOR,
              "on",
              ">" + INSPECTOR_PASSWORD,
              "~*",
              "&*",
              "+@all");

  @TestConfiguration(proxyBeanMethods = false)
  static class AclRedis {
    @Bean
    @Primary
    DataRedisConnectionDetails aclRedisConnectionDetails(Environment environment) {
      if (!VALKEY.isRunning()) VALKEY.start();
      // 운영처럼 사용자·비밀번호는 spring.data.redis.username·password 설정 값에서(공통 테스트 설정의 연결 정보 빈이 설정 값 경로를 덮으므로
      // 같은 값을 읽어 넘김).
      var username = environment.getRequiredProperty("spring.data.redis.username");
      var password = environment.getRequiredProperty("spring.data.redis.password");
      return new DataRedisConnectionDetails() {
        @Override
        public String getUsername() {
          return username;
        }

        @Override
        public String getPassword() {
          return password;
        }

        @Override
        public Standalone getStandalone() {
          return Standalone.of(VALKEY.getHost(), VALKEY.getMappedPort(6379), 0);
        }
      };
    }
  }

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;
  @Autowired LostArkRequestGate gate;
  @Autowired LeaderLease lease;
  @Autowired StringRedisTemplate redis;
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(int target, String path, String forwardedFor) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + target + path)).GET();
    if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 확인 전용 사용자로 valkey-cli 실행(앱 연결과 따로). */
  private static String inspect(String... command) throws Exception {
    var args = new ArrayList<String>();
    args.addAll(
        List.of(
            "valkey-cli", "--user", INSPECTOR, "--pass", INSPECTOR_PASSWORD, "--no-auth-warning"));
    args.addAll(List.of(command));
    var result = VALKEY.execInContainer(args.toArray(String[]::new));
    return (result.getStdout() + result.getStderr()).strip();
  }

  /** 앱 사용자가 이 명령을 쓸 수 있는지(ACL DRYRUN, 실행하지 않음). */
  private static String dryRun(String... command) throws Exception {
    var args = new ArrayList<String>(List.of("ACL", "DRYRUN", USER));
    args.addAll(List.of(command));
    return inspect(args.toArray(String[]::new));
  }

  @Test
  @Order(1)
  void 앱_사용자로_연결_게이트_한도_캐시_임대_집합_준비상태가_거부_없이_된다() throws Exception {
    // 앱 컨텍스트가 뜨는 동안(연결 인사 HELLO·AUTH·CLIENT SETINFO, 시작 작업) 거부가 없었다. 위험 명령 확인(KEYS)은 이 테스트 뒤에 돈다.
    assertThat(inspect("ACL", "LOG")).isEmpty();
    var connection = redis.getConnectionFactory().getConnection();
    try {
      assertThat(connection.ping()).isEqualTo("PONG");
    } finally {
      connection.close();
    }
    // 공식 호출 게이트(Lua EVALSHA → 처음은 EVAL: TIME·ZADD·ZCARD·ZREMRANGEBYSCORE·PERSIST·SET·GET).
    gate.acquire();
    gate.release();
    gate.acquire();
    gate.release();
    // 클라이언트 한도(Lua: INCR·PEXPIRE·GET·DECR)와 통계 캐시(SET·GET, TTL): 같은 요청 두 번(두 번째는 EVALSHA·캐시 적중).
    assertThat(get(port, "/api/v1/stats/summary", "203.0.113.70").statusCode()).isEqualTo(200);
    assertThat(get(port, "/api/v1/stats/summary", "203.0.113.70").statusCode()).isEqualTo(200);
    // 대표 실행 임대(Lua: GET·SET·PEXPIRE·DEL).
    assertThat(lease.hold("acl-test", Duration.ofSeconds(30))).isTrue();
    lease.release("acl-test");
    // 집합(인기 검색·상점 공지가 쓰는 SADD·SMEMBERS)과 그 밖의 값 명령(SET NX PX·PTTL·EXISTS·MGET·EXPIRE·DEL).
    redis.opsForSet().add("acl:test:set", "a");
    assertThat(redis.opsForSet().members("acl:test:set")).containsExactly("a");
    assertThat(redis.opsForValue().setIfAbsent("acl:test:nx", "1", Duration.ofSeconds(30)))
        .isTrue();
    assertThat(redis.getExpire("acl:test:nx")).isPositive();
    assertThat(redis.hasKey("acl:test:nx")).isTrue();
    assertThat(redis.opsForValue().multiGet(List.of("acl:test:nx", "acl:test:none")))
        .containsExactly("1", null);
    assertThat(redis.opsForValue().increment("acl:test:count")).isEqualTo(1L);
    assertThat(redis.expire("acl:test:count", Duration.ofSeconds(30))).isTrue();
    redis.delete(List.of("acl:test:set", "acl:test:nx", "acl:test:count"));
    // 준비 상태: Valkey 상태 확인(INFO)까지 UP.
    var readiness = get(managementPort, "/actuator/health/readiness", null);
    assertThat(readiness.statusCode()).as(readiness.body()).isEqualTo(200);
    var health = get(managementPort, "/actuator/health", null);
    assertThat(health.statusCode()).as(health.body()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");
    // 그동안 앱 사용자 명령이 하나도 거부되지 않았다(빈 ACL LOG).
    assertThat(inspect("ACL", "LOG")).isEmpty();
  }

  @Test
  @Order(2)
  void 앱이_쓰는_명령은_모두_허용되고_KEYS_SCAN_같은_위험_명령만_막힌다() throws Exception {
    // 연결 인사와 Lua 실행 경로.
    for (var command :
        List.of(
            List.of("HELLO", "3"),
            List.of("AUTH", USER, PASSWORD),
            List.of("CLIENT", "SETNAME", "loakraken"),
            List.of("CLIENT", "SETINFO", "LIB-NAME", "Lettuce"),
            List.of("CLIENT", "SETINFO", "LIB-VER", "6"),
            List.of("PING"),
            List.of("INFO"),
            List.of("EVAL", "return 1", "0"),
            List.of("EVALSHA", "e0e1f9fabfc9d4800c877a703b823ac0578ff8db", "0"),
            List.of("SCRIPT", "LOAD", "return 1"),
            // 값·집합·정렬 집합(앱 코드와 Lua 안에서 부르는 것).
            List.of("GET", "k"),
            List.of("SET", "k", "v", "NX", "PX", "1000"),
            List.of("MGET", "k", "j"),
            List.of("DEL", "k"),
            List.of("EXISTS", "k"),
            List.of("INCR", "k"),
            List.of("DECR", "k"),
            List.of("EXPIRE", "k", "10"),
            List.of("PEXPIRE", "k", "10"),
            List.of("PERSIST", "k"),
            List.of("TTL", "k"),
            List.of("PTTL", "k"),
            List.of("TIME"),
            List.of("ZADD", "z", "1", "a"),
            List.of("ZCARD", "z"),
            List.of("ZREMRANGEBYSCORE", "z", "0", "1"),
            List.of("SADD", "s", "a"),
            List.of("SMEMBERS", "s"))) {
      assertThat(dryRun(command.toArray(String[]::new)))
          .as(String.join(" ", command))
          .isEqualTo("OK");
    }
    // 앱은 KEYS를 쓰지 않는다(위험 분류라 막힘). SCAN은 위험 분류가 아니라 허용되지만 앱 코드에 쓰는 곳이 없다.
    assertThat(dryRun("KEYS", "*")).contains("no permissions");
    assertThat(dryRun("SCAN", "0")).isEqualTo("OK");
    assertThat(dryRun("FLUSHALL")).contains("no permissions");
    assertThat(dryRun("CONFIG", "GET", "*")).contains("no permissions");
  }

  @Test
  @Order(3)
  void 위험_명령은_앱_사용자에게_막혀_있다() throws Exception {
    // 규칙이 실제로 걸려 있는지(테스트가 아무 규칙 없이 통과하지 않게).
    assertThatThrownBy(() -> redis.keys("*")).rootCause().hasMessageContaining("NOPERM");
    // 거부 기록(ACL LOG)이 실제로 남는지 — 첫 테스트의 '빈 기록' 확인이 그냥 통과하지 않게.
    assertThat(inspect("ACL", "LOG")).contains("keys").contains(USER);
  }
}
