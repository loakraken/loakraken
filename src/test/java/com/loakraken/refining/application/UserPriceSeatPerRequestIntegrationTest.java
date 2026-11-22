package com.loakraken.refining.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.loakraken.TestcontainersConfiguration;
import com.loakraken.common.api.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 사용자 가격 특재 가치 풀이 자리는 요청 하나가 처음 캐시에 없는 단계에서 받아 요청이 끝날 때까지 쥔다(PM 총괄 2026-11-22, #1101 리뷰 메모). 그래서 이미
 * 풀기 시작한 요청은 끝까지 가고, 그사이 들어온 다른 풀이가 바로 429를 받는다(중간에 일한 뒤 429가 아니라).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"refining.special-stone.enabled=true", "refining.optimal.enabled=true"})
class UserPriceSeatPerRequestIntegrationTest {
  @LocalServerPort int port;
  @Autowired com.loakraken.market.application.MarketCache marketCache;
  @Autowired StringRedisTemplate redis;
  @Autowired UserPriceSolvePool pool;
  @MockitoSpyBean RefiningPlanService plans;

  @BeforeEach
  @AfterEach
  void 시세_정리() {
    redis.delete("market:v1:refining-materials");
  }

  @Test
  void 풀기_시작한_요청은_단계_사이에_다른_풀이가_와도_끝까지_간다() throws Exception {
    capturePrices();
    var calls = new AtomicInteger();
    var otherSettled = new CountDownLatch(1);
    var releaseOther = new CountDownLatch(1);
    var otherRejected = new AtomicBoolean();
    var other =
        new Thread(
            () -> {
              try {
                pool.get(
                    "seat-per-request-other",
                    () -> {
                      otherSettled.countDown();
                      try {
                        releaseOther.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    });
              } catch (BusinessException exception) {
                otherRejected.set(true);
                otherSettled.countDown();
              }
            });
    // 첫 단계를 다 푼 뒤(다음 단계 전) 다른 풀이를 끼워 넣는다.
    doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              if (calls.incrementAndGet() == 1) {
                other.start();
                assertThat(otherSettled.await(5, TimeUnit.SECONDS)).isTrue();
              }
              return result;
            })
        .when(plans)
        .optimalWith(any(), any(), any(), any(), any());
    try {
      var response =
          request(
              // 20 → 21, 21 → 22는 책이 없는 단계(캡처 시세로 둘 다 계산됨, 계산 검증 2026-11-22).
              "{\"targets\":[{\"grade\":\"JEONYUL\",\"equipmentType\":\"WEAPON\",\"fromStage\":20},"
                  + "{\"grade\":\"JEONYUL\",\"equipmentType\":\"WEAPON\",\"fromStage\":21}],"
                  + "\"prices\":[{\"name\":\"운명의 파괴석 결정\",\"unitPriceGold\":12.34}]}");
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      // 두 단계 모두 풀었다(끼워 넣기가 실제로 단계 사이에서 일어났다).
      assertThat(calls).hasValue(2);
      assertThat(response.body()).doesNotContain("PRICE_MISSING").contains("\"status\":\"OK\"");
      assertThat(otherRejected).as("요청이 자리를 쥔 동안 다른 풀이는 바로 429").isTrue();
    } finally {
      releaseOther.countDown();
      if (other.isAlive() || other.getState() != Thread.State.NEW) other.join(5_000);
    }
  }

  private void capturePrices() {
    var items = new ArrayList<com.loakraken.market.domain.MarketPrice>();
    items.add(price(1, "운명의 파괴석 결정", 100, "1036"));
    items.add(price(2, "위대한 운명의 돌파석", 1, "19"));
    items.add(price(3, "상급 아비도스 융화 재료", 1, "221"));
    items.add(price(4, "운명의 파편 주머니(소)", 1, "80"));
    items.add(price(5, "운명의 수호석 결정", 100, "174"));
    items.add(price(6, "빙하의 숨결", 1, "385"));
    items.add(price(7, "용암의 숨결", 1, "433"));
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            Instant.now(), List.copyOf(items)),
        java.time.Duration.ofMinutes(5));
  }

  private static com.loakraken.market.domain.MarketPrice price(
      long id, String name, int bundle, String currentMin) {
    return new com.loakraken.market.domain.MarketPrice(
        id, name, "영웅", null, bundle, null, null, null, new java.math.BigDecimal(currentMin));
  }

  private HttpResponse<String> request(String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:" + port + "/api/v1/refining/special-stone-values"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
