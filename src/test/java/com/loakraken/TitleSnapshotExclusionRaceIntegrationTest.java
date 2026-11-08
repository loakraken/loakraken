package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.loakraken.stats.application.StatsPort;
import com.loakraken.stats.application.TitleSnapshotService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 칭호 일별 스냅숏과 처리정지 목록의 경합(#864 보안 재점검): 만드는 도중 처리정지 캐릭터가 목록에서 빠졌다가 다시 들어가면 목록 값은 처음과 같아진다. 그
 * 사이에 센 값이 그 캐릭터를 포함하면 지금 목록 값으로 저장돼 그대로 보이면 안 된다. 만들기 전체가 한 스냅숏에서 목록 값과 집계를 읽어야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.titles.enabled=true")
class TitleSnapshotExclusionRaceIntegrationTest {
  private static final String SERVER = "칭호경합서버";
  private static final String NAME = "칭호경합";
  private static final String TITLE = "경합 시험 칭호";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired TitleSnapshotService snapshots;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean StatsPort stats;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '칭호경합%'").update();
    jdbc.sql("DELETE FROM title_equip_daily").update();
    jdbc.sql("DELETE FROM title_equip_daily_meta").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 만드는_도중_처리정지가_빠졌다가_다시_들어가도_그_캐릭터가_세어진_날짜를_주지_않는다() throws Exception {
    character(NAME, TITLE);
    character("칭호경합둘", "혼돈의 지배자");
    exclude(true);
    // 첫 집계 직전에 목록에서 빼고, 첫 집계 직후 다시 넣는다(다른 트랜잭션에서 바로 커밋). 목록 값은 처음과 같아진다.
    var armed = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              boolean race = armed.getAndSet(false);
              if (race) exclude(false);
              try {
                return invocation.callRealMethod();
              } finally {
                if (race) exclude(true);
              }
            })
        .when(stats)
        .titleEquip(any(), any(), any(), any());

    snapshots.build();
    assertThat(armed.get()).as("경합 훅이 실행됨").isFalse();

    var today = LocalDate.now(TitleSnapshotService.KST);
    // 그 캐릭터가 세어진 값은 보이면 안 된다: 그 날짜를 주지 않거나(지난 목록 값), 주더라도 그 칭호가 없다.
    var during = get("/api/v1/titles/equipped?date=" + today + "&title=" + encode(TITLE));
    if (during.statusCode() == 200)
      assertThat(mapper.readTree(during.body()).at("/data/titles").size())
          .as("처리정지 캐릭터가 세어진 스냅숏이 지금 목록 값으로 저장됨")
          .isZero();
    else assertThat(during.statusCode()).isEqualTo(404);

    // 다음 만들기(경합 없음)는 지금 목록으로 오늘을 만들고 그 캐릭터를 세지 않는다.
    snapshots.build();
    var after = get("/api/v1/titles/equipped?date=" + today + "&title=" + encode(TITLE));
    assertThat(after.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(after.body()).at("/data/titles").size()).isZero();
  }

  /** 처리정지 목록에 넣거나 뺀다. 만들기 트랜잭션과 따로 바로 커밋한다. */
  private void exclude(boolean on) {
    var template = new TransactionTemplate(transactions);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.executeWithoutResult(
        status -> {
          if (on)
            jdbc.sql(
                    "INSERT INTO public_exclusion (character_name, server_name, reason_code)"
                        + " VALUES (:n, NULL, 'USER_REQUEST')")
                .param("n", NAME)
                .update();
          else
            jdbc.sql("DELETE FROM public_exclusion WHERE character_name = :n")
                .param("n", NAME)
                .update();
        });
  }

  private void character(String name, String title) {
    var id = UUID.randomUUID();
    var time = Instant.now().atOffset(ZoneOffset.UTC);
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              last_synced_at, profile_refreshed_at, roster_key, roster_scanned_at)
            VALUES (:id, :name, :server, '블레이드', :level, :at, :at, :roster, :at)
            """)
        .param("id", id)
        .param("name", name)
        .param("server", SERVER)
        .param("level", new BigDecimal("1700"))
        .param("at", time)
        .param("roster", UUID.randomUUID())
        .update();
    jdbc.sql(
            """
            INSERT INTO character_title_observation (character_id, title, first_observed_at, last_observed_at)
            VALUES (:id, :title, :at, :at)
            """)
        .param("id", id)
        .param("title", title)
        .param("at", time)
        .update();
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
