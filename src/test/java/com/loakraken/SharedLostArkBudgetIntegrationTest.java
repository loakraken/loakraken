package com.loakraken;

import static org.assertj.core.api.Assertions.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SharedLostArkBudgetIntegrationTest {
  private static final AtomicInteger calls = new AtomicInteger();
  private static final HttpServer server = server();
  @Autowired CharacterDetailsPort characters;
  @Autowired MarketPricesPort markets;
  @Autowired ApplicationContext context;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("lostark.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    properties.add("lostark.api-key", () -> "local-test-token");
    properties.add("lostark.requests-per-minute", () -> "3");
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @Test
  void 캐릭터와_거래소는_단일_호출_예산을_공유한다() {
    assertThat(context.getBeansOfType(LostArkRequestGate.class)).hasSize(1);
    characters.fetch("모험가");
    var search =
        new MarketSearch(
            50000,
            "",
            null,
            "",
            "",
            1,
            MarketSearch.Sort.RECENT_PRICE,
            MarketSearch.SortCondition.ASC);
    markets.fetch(MarketSource.ACTIVE_LISTING, search);
    assertThat(calls).hasValue(3);
    assertThatThrownBy(() -> markets.fetch(MarketSource.RECENT_TRADE, search))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode().code()).isEqualTo("LOST_ARK_RATE_LIMITED"));
    assertThatThrownBy(() -> characters.fetch("다른모험가"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode().code()).isEqualTo("LOST_ARK_RATE_LIMITED"));
    assertThat(calls).hasValue(3);
  }

  private static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String json =
                path.startsWith("/armories/")
                    ? """
            {"ArmoryProfile":{"CharacterName":"모험가","ServerName":"루페온",
            "CharacterClassName":"바드","CharacterLevel":70,"ItemAvgLevel":"1700.5"}}
            """
                    : path.endsWith("/siblings")
                        ? "[]"
                        : "{\"PageNo\":1,\"PageSize\":10,\"TotalCount\":0,\"Items\":[]}";
            exchange.getRequestBody().readAllBytes();
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
