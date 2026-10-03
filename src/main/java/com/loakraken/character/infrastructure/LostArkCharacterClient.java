package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.domain.CharacterDetails;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class LostArkCharacterClient implements CharacterDetailsPort {
  private static final String FILTERS =
      "profiles+equipment+engravings+gems+cards+arkpassive+arkgrid";
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
  public CharacterDetails fetch(String characterName) {
    var armory =
        request(
            "/armories/characters/{name}?filters={filters}",
            characterName,
            LostArkArmoryResponse.class,
            CharacterErrorCode.CHARACTER_NOT_FOUND);
    if (armory == null) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
    if (armory.profile() == null)
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    var profile = LostArkProfileMapper.from(armory.profile());
    var siblings =
        request(
            "/characters/{name}/siblings",
            characterName,
            LostArkSiblingResponse[].class,
            CharacterErrorCode.LOST_ARK_API_ERROR);
    if (siblings == null) throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    return new CharacterDetails(
        profile,
        armory.toLoadout(),
        Arrays.stream(siblings)
            .filter(Objects::nonNull)
            .map(LostArkSiblingResponse::toDomain)
            .toList());
  }

  private <T> T request(
      String path, String characterName, Class<T> responseType, CharacterErrorCode notFoundCode) {
    if (properties.apiKey().isBlank())
      throw new CharacterException(CharacterErrorCode.LOST_ARK_NOT_CONFIGURED);
    gate.acquire();
    try {
      return client
          .get()
          .uri(path, Map.of("name", characterName, "filters", FILTERS))
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                gate.observe(response.getHeaders(), status == 429);
                if (status == 404) throw new CharacterException(notFoundCode);
                if (status == 429)
                  throw new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED);
                if (status != 200)
                  throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
                return response.bodyTo(responseType);
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
