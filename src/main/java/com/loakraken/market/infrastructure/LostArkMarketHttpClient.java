package com.loakraken.market.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.*;
import com.loakraken.common.lostark.LostArkMetrics.ApiOutcome;
import com.loakraken.common.lostark.LostArkMetrics.Endpoint;
import com.loakraken.market.application.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class LostArkMarketHttpClient {
  private final RestClient client;
  private final LostArkProperties properties;
  private final LostArkRequestGate gate;
  private final LostArkMetrics metrics;
  private final JsonMapper mapper;

  LostArkMarketHttpClient(
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

  <T> T request(Endpoint endpoint, MarketSearch search, Class<T> responseType) {
    if (properties.apiKey().isBlank()) throw error(LostArkErrorCode.LOST_ARK_NOT_CONFIGURED);
    T payload;
    RestClient.RequestHeadersSpec<?> request;
    if (endpoint == Endpoint.MARKET_OPTIONS) {
      request = client.get().uri("/markets/options");
    } else if (endpoint == Endpoint.MARKET_ITEMS || endpoint == Endpoint.MARKET_TRADES) {
      request =
          client
              .post()
              .uri(endpoint == Endpoint.MARKET_ITEMS ? "/markets/items" : "/markets/trades")
              .contentType(MediaType.APPLICATION_JSON)
              .body(mapper.writeValueAsString(Request.from(search)));
    } else {
      throw new IllegalArgumentException("지원하지 않는 거래소 API");
    }
    gate.acquire();
    var sample = metrics.startApi();
    var outcome = new AtomicReference<>(ApiOutcome.ERROR);
    try {
      payload =
          request.exchange(
              (sentRequest, response) -> {
                int status = response.getStatusCode().value();
                gate.observe(response.getHeaders(), status == 429);
                if (status == 429) {
                  outcome.set(ApiOutcome.RATE_LIMITED);
                  throw error(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
                }
                if (status == 404) outcome.set(ApiOutcome.NOT_FOUND);
                if (status != 200) throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
                String json = response.bodyTo(String.class);
                if (json == null || json.isBlank())
                  throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
                var value = mapper.readValue(json, responseType);
                outcome.set(ApiOutcome.SUCCESS);
                return value;
              });
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
    if (payload == null) throw error(LostArkErrorCode.LOST_ARK_API_ERROR);
    return payload;
  }

  private BusinessException error(LostArkErrorCode code) {
    return new BusinessException(code);
  }

  private record Request(
      @JsonProperty("CategoryCode") int categoryCode,
      @JsonProperty("CharacterClass") String characterClass,
      @JsonProperty("ItemTier") Integer itemTier,
      @JsonProperty("ItemGrade") String itemGrade,
      @JsonProperty("ItemName") String itemName,
      @JsonProperty("PageNo") int pageNo,
      @JsonProperty("Sort") MarketSearch.Sort sort,
      @JsonProperty("SortCondition") MarketSearch.SortCondition sortCondition) {
    static Request from(MarketSearch search) {
      return new Request(
          search.categoryCode(),
          search.characterClass(),
          search.itemTier(),
          search.itemGrade(),
          search.itemName(),
          search.pageNo(),
          search.sort(),
          search.sortCondition());
    }
  }
}
