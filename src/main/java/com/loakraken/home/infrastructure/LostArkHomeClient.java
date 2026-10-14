package com.loakraken.home.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkMetrics;
import com.loakraken.common.lostark.LostArkMetrics.ApiOutcome;
import com.loakraken.common.lostark.LostArkMetrics.Endpoint;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.home.application.HomeSourcePort;
import com.loakraken.home.domain.GameCalendar;
import com.loakraken.home.domain.NewsFeed;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 공식 GAMECONTENTS·NEWS GET 조회. 거래소·캐릭터와 같은 호출 제어기(분당 90회·동시 4개)를 공유한다. */
@Component
class LostArkHomeClient implements HomeSourcePort {
  private final RestClient client;
  private final LostArkProperties properties;
  private final LostArkRequestGate gate;
  private final LostArkMetrics metrics;
  private final JsonMapper mapper;

  LostArkHomeClient(
      RestClient lostArkRestClient,
      LostArkProperties properties,
      LostArkRequestGate gate,
      LostArkMetrics metrics,
      JsonMapper mapper) {
    this.client = lostArkRestClient;
    this.properties = properties;
    this.gate = gate;
    this.metrics = metrics;
    this.mapper = mapper;
  }

  public GameCalendar fetchCalendar() {
    var items =
        get(Endpoint.GAME_CALENDAR, "/gamecontents/calendar", LostArkCalendarResponse[].class);
    return LostArkCalendarResponse.toDomain(items);
  }

  public NewsFeed fetchNews() {
    var notices = get(Endpoint.NEWS_NOTICES, "/news/notices", LostArkNewsResponse.Notice[].class);
    var events = get(Endpoint.NEWS_EVENTS, "/news/events", LostArkNewsResponse.Event[].class);
    return LostArkNewsResponse.toDomain(notices, events);
  }

  private <T> T get(Endpoint endpoint, String path, Class<T> type) {
    if (properties.apiKey().isBlank()) throw error(LostArkErrorCode.LOST_ARK_NOT_CONFIGURED);
    gate.acquire(endpoint);
    var sample = metrics.startApi();
    var outcome = new AtomicReference<>(ApiOutcome.ERROR);
    try {
      T payload =
          client
              .get()
              .uri(path)
              .exchange(
                  (request, response) -> {
                    int status = response.getStatusCode().value();
                    gate.observe(response.getHeaders(), status == 429);
                    if (status == 429) {
                      outcome.set(ApiOutcome.RATE_LIMITED);
                      throw error(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
                    }
                    if (status != 200) throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
                    String json = response.bodyTo(String.class);
                    if (json == null || json.isBlank())
                      throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
                    var value = mapper.readValue(json, type);
                    if (value == null) throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
                    outcome.set(ApiOutcome.SUCCESS);
                    return value;
                  });
      return payload;
    } catch (ResourceAccessException exception) {
      for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof java.net.http.HttpTimeoutException
            || cause instanceof java.net.SocketTimeoutException) {
          outcome.set(ApiOutcome.TIMEOUT);
          throw error(LostArkErrorCode.LOST_ARK_API_TIMEOUT);
        }
      }
      throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
    } catch (RestClientException | JacksonException exception) {
      throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
    } finally {
      gate.release();
      metrics.finishApi(sample, endpoint, outcome.get());
    }
  }

  private static BusinessException error(LostArkErrorCode code) {
    return new BusinessException(code);
  }
}
