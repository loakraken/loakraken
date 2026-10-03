package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterProfilePort;
import com.loakraken.character.domain.CharacterProfile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class LostArkCharacterClient implements CharacterProfilePort {
  private final RestClient client;
  private final LostArkProperties properties;
  private final LostArkRequestGate gate;

  LostArkCharacterClient(
      RestClient lostArkRestClient, LostArkProperties properties, LostArkRequestGate gate) {
    this.client = lostArkRestClient;
    this.properties = properties;
    this.gate = gate;
  }

  @Override
  public CharacterProfile fetch(String characterName) {
    if (properties.apiKey().isBlank())
      throw new CharacterException(CharacterErrorCode.LOST_ARK_NOT_CONFIGURED);
    gate.acquire();
    try {
      return client
          .get()
          .uri("/armories/characters/{name}/profiles", characterName)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                gate.observe(response.getHeaders(), status == 429);
                if (status == 404)
                  throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
                if (status == 429)
                  throw new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED);
                if (status != 200)
                  throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
                return LostArkProfileMapper.from(response.bodyTo(LostArkProfileResponse.class));
              });
    } catch (ResourceAccessException exception) {
      Throwable cause = exception;
      while (cause != null) {
        if (cause instanceof java.net.http.HttpTimeoutException
            || cause instanceof java.net.SocketTimeoutException) {
          throw new CharacterException(CharacterErrorCode.LOST_ARK_API_TIMEOUT);
        }
        cause = cause.getCause();
      }
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    } catch (RestClientException exception) {
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    } finally {
      gate.release();
    }
  }
}
