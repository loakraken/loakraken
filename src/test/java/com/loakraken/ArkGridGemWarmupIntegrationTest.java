package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.MarketCache;
import com.loakraken.market.domain.MarketTab;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 젬 교체 순위 예열(PM 총괄 2026-11-22): 젬 교체 순위 스위치(combat-power-sim.gem-ranking.enabled)가 켜져 있을 때만 아크 그리드
 * 탭 전체 스캔을 주기마다 새로 받아 캐시에 둔다(순위는 캐시만 읽음). 꺼져 있으면 공식 호출 0. 스위치는 틱마다 읽는다. 공식 API는 가짜 서버다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "market.warmup.enabled=true",
      "market.warmup.initial-delay=0s",
      "market.warmup.ark-grid-interval=1s",
      "market.cache.prices-ttl=1s",
      "combat-power-sim.gem-ranking.enabled=false"
    })
class ArkGridGemWarmupIntegrationTest {
  private static final JsonMapper MAPPER = new JsonMapper();
  private static final AtomicInteger arkGridSearches = new AtomicInteger();
  private static final HttpServer server = server();

  @Autowired ConfigurableEnvironment environment;
  @Autowired MarketCache cache;
  @Autowired com.loakraken.market.application.ArkGridGemPriceService gemPrices;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("lostark.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    properties.add("lostark.api-key", () -> "local-test-token");
    properties.add("lostark.requests-per-minute", () -> "600");
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @Test
  void 젬_교체_순위_스위치가_켜져_있을_때만_아크_그리드_스캔을_주기마다_새로_받는다() throws Exception {
    // 꺼져 있으면 3초(예열 주기 1초) 동안 아크 그리드 검색 0.
    Thread.sleep(3_000);
    assertThat(arkGridSearches).hasValue(0);
    // 켜면 주기마다 새로 받는다(캐시에 있어도 다시 받음): 두 번 이상, 캐시에 스캔이 있다.
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "gem-ranking-on", Map.of("combat-power-sim.gem-ranking.enabled", "true")));
    long deadline = System.currentTimeMillis() + 20_000;
    while (arkGridSearches.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(200);
    assertThat(arkGridSearches.get()).as("아크 그리드 1쪽 검색 수").isGreaterThanOrEqualTo(2);
    assertThat(cache.findBoardScan(MarketTab.ARK_GRID, MarketTab.ARK_GRID.defaultTier()))
        .isPresent();
    // 순위가 읽는 젬 시세(캐시만): 예열이 받은 안정 영웅 1,000골드.
    assertThat(gemPrices.cachedPrices())
        .anySatisfy(
            price -> {
              assertThat(price.gemId()).isEqualTo(67400003L);
              assertThat(price.unitPrice()).isEqualTo(1_000L);
            });
  }

  /** 아크 그리드(230000) 1쪽 검색만 센다. 다른 예열 요청은 빈 쪽을 준다. */
  private static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            String json = "{\"PageNo\":1,\"PageSize\":10,\"TotalCount\":0,\"Items\":[]}";
            if ("POST".equals(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().equals("/markets/items")) {
              var request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
              if (request.path("CategoryCode").asInt() == 230000) {
                if (request.path("PageNo").asInt(1) == 1) arkGridSearches.incrementAndGet();
                json =
                    "{\"PageNo\":1,\"PageSize\":10,\"TotalCount\":1,\"Items\":[{\"Id\":67400003,\"Name\":\"질서의 젬 : 안정\",\"Grade\":\"영웅\",\"BundleCount\":1,\"RecentPrice\":1000,\"CurrentMinPrice\":1000}]}";
              }
            } else exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }
}
