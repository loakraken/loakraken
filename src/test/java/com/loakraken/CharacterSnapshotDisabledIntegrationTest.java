package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterSnapshotHistoryPort;
import com.loakraken.character.application.CharacterSnapshotPolicy;
import com.loakraken.character.application.CharacterSnapshotPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CharacterSnapshotDisabledIntegrationTest {
  private static final String NAME = "저장비활성테스트";
  private static final String KEY = "character:v7:" + NAME + ":summary";
  @LocalServerPort private int port;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JsonMapper mapper;
  @Autowired private CharacterSnapshotPolicy policy;
  @MockitoBean private CharacterDetailsPort details;
  @MockitoBean private CharacterSnapshotPort snapshots;
  @MockitoBean private CharacterSnapshotHistoryPort history;
  @MockitoSpyBean private PlatformTransactionManager transactions;

  @BeforeEach
  void 준비() {
    assertThat(policy.enabled()).isFalse();
    redis.delete(KEY);
    clearInvocations(transactions);
  }

  @AfterEach
  void 정리() {
    verifyNoInteractions(snapshots, history, transactions);
    redis.delete(KEY);
  }

  @Test
  void 기본_설정에서는_DB_저장과_트랜잭션_없이_검색과_캐시가_동작한다() throws Exception {
    var profile =
        new CharacterProfile(
            NAME, "루페온", "바드", 70, new BigDecimal("1700"), null, 300, null, null, null, List.of());
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null);
    when(details.fetch(NAME))
        .thenReturn(
            new FetchedCharacter(
                com.loakraken.CharacterTestFixture.details(profile, loadout, List.of()),
                "{}",
                "[]"));
    String path = "/api/v1/characters/search?characterName=" + encode(NAME);
    var first = request(path);
    var second = request(path);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(redis.getExpire(KEY)).isBetween(1L, 300L);
    verify(details, times(1)).fetch(NAME);
  }

  @Test
  void 비활성_이력은_빈_목록_대신_명시적인_503을_반환한다() throws Exception {
    var response =
        request(
            "/api/v1/characters/snapshots?characterName="
                + encode(NAME)
                + "&serverName="
                + encode("루페온"));
    assertThat(response.statusCode()).isEqualTo(503);
    var body = mapper.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo("CHARACTER_HISTORY_DISABLED");
    assertThat(body.path("data").isNull()).isTrue();
    ApiContractFixture.write("snapshot-history-disabled.json", response.body());
    verifyNoInteractions(details);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private HttpResponse<String> request(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
