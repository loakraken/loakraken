package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkMetrics;
import com.loakraken.common.lostark.LostArkMetrics.ApiOutcome;
import com.loakraken.common.lostark.LostArkMetrics.Endpoint;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class LostArkCharacterClient implements CharacterDetailsPort {
  private static final String FILTERS =
      "profiles+equipment+engravings+gems+cards+arkpassive+arkgrid+combat-skills+avatars+collectibles";
  private final RestClient client;
  private final LostArkProperties properties;
  private final LostArkRequestGate gate;
  private final JsonMapper mapper;
  private final LostArkMetrics metrics;
  private final org.springframework.beans.factory.ObjectProvider<ArmoryRawDump> rawDump;

  LostArkCharacterClient(
      RestClient lostArkRestClient,
      LostArkProperties properties,
      LostArkRequestGate gate,
      JsonMapper mapper,
      LostArkMetrics metrics,
      org.springframework.beans.factory.ObjectProvider<ArmoryRawDump> rawDump) {
    this.client = lostArkRestClient;
    this.properties = properties;
    this.gate = gate;
    this.mapper = mapper;
    this.metrics = metrics;
    this.rawDump = rawDump;
  }

  @Override
  public FetchedCharacter fetch(String characterName) {
    var armoryPayload = armory(characterName);
    var armory = armoryPayload.value();
    var profile = LostArkProfileMapper.from(armory.profile());
    // 로컬 대조 전용(#540): local 프로필 + ARMORY_RAW_DUMP_DIR일 때만, 처리정지 목록 밖 캐릭터의 본문을 파일로 남긴다.
    rawDump.ifAvailable(dump -> dump.write(profile, armoryPayload.json()));
    var siblingsPayload = siblings(characterName, CharacterErrorCode.LOST_ARK_API_ERROR);
    var siblings = siblingsPayload.value();
    var details =
        new CharacterDetails(
            profile,
            armory.toLoadout(),
            toDomain(siblings),
            armory.coverage(Arrays.asList(siblings)));
    return new FetchedCharacter(details, armoryPayload.json(), siblingsPayload.json());
  }

  @Override
  public FetchedCharacter fetchArmory(String characterName) {
    var armoryPayload = armory(characterName);
    var armory = armoryPayload.value();
    var profile = LostArkProfileMapper.from(armory.profile());
    rawDump.ifAvailable(dump -> dump.write(profile, armoryPayload.json()));
    var details = new CharacterDetails(profile, armory.toLoadout(), null, armory.coverage(null));
    return new FetchedCharacter(details, armoryPayload.json(), null);
  }

  @Override
  public List<CharacterSibling> fetchSiblings(String characterName) {
    var siblings = siblings(characterName, CharacterErrorCode.CHARACTER_NOT_FOUND).value();
    var result = toDomain(siblings);
    // 공식 원정대 API는 없는 캐릭터에 200과 빈 배열을 반환한다. 원정대에는 조회한 캐릭터 자신이 포함된다.
    if (result.isEmpty()) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
    return result;
  }

  @Override
  public CharacterProfile fetchProfile(String characterName, int reserve) {
    // 공식 /armories/characters/{name}/profiles(약 7KB)에 CombatPower가 있다(2026-10-12 확인). 전체 armory보다
    // 가볍다.
    var payload =
        // 사용자가 누른 불러오기라 사용자 몫을 남기면서 그 클라이언트의 공식 호출 한도에도 매긴다.
        gate.withUserReserve(
            reserve,
            () ->
                request(
                    Endpoint.PROFILE,
                    "/armories/characters/{name}/profiles",
                    characterName,
                    LostArkProfileResponse.class,
                    CharacterErrorCode.CHARACTER_NOT_FOUND));
    if (payload.value() == null)
      throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
    return LostArkProfileMapper.from(payload.value());
  }

  private Payload<LostArkArmoryResponse> armory(String characterName) {
    var payload =
        request(
            Endpoint.ARMORY,
            "/armories/characters/{name}?filters={filters}",
            characterName,
            LostArkArmoryResponse.class,
            CharacterErrorCode.CHARACTER_NOT_FOUND);
    if (payload.value() == null)
      throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
    if (payload.value().profile() == null)
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    return payload;
  }

  private Payload<LostArkSiblingResponse[]> siblings(
      String characterName, CharacterErrorCode notFoundCode) {
    var payload =
        request(
            Endpoint.SIBLINGS,
            "/characters/{name}/siblings",
            characterName,
            LostArkSiblingResponse[].class,
            notFoundCode);
    if (payload.value() == null)
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    return payload;
  }

  private static List<CharacterSibling> toDomain(LostArkSiblingResponse[] siblings) {
    return Arrays.stream(siblings)
        .filter(Objects::nonNull)
        .map(LostArkSiblingResponse::toDomain)
        .toList();
  }

  private record Payload<T>(T value, String json) {}

  private <T> Payload<T> request(
      Endpoint endpoint,
      String path,
      String characterName,
      Class<T> responseType,
      CharacterErrorCode notFoundCode) {
    if (properties.apiKey().isBlank())
      throw new CharacterException(CharacterErrorCode.LOST_ARK_NOT_CONFIGURED);
    try {
      gate.acquire(endpoint);
    } catch (BusinessException exception) {
      // 게이트 저장소 장애는 호출 한도가 아니라 503이다(#107).
      // 클라이언트 한도(#108)도 공유 한도와 구분해 그대로 돌려준다.
      if (exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE
          || exception.errorCode() == LostArkErrorCode.CLIENT_RATE_LIMITED) throw exception;
      throw new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED);
    }
    var sample = metrics.startApi();
    var outcome = new AtomicReference<>(ApiOutcome.ERROR);
    try {
      return client
          .get()
          .uri(path, Map.of("name", characterName, "filters", FILTERS))
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                gate.observe(response.getHeaders(), status == 429);
                if (status == 404) {
                  outcome.set(ApiOutcome.NOT_FOUND);
                  throw new CharacterException(notFoundCode);
                }
                if (status == 429) {
                  outcome.set(ApiOutcome.RATE_LIMITED);
                  throw new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED);
                }
                if (status != 200)
                  throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
                String json = response.bodyTo(String.class);
                if (json == null || json.isBlank())
                  throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
                var payload = new Payload<>(mapper.readValue(json, responseType), json);
                outcome.set(ApiOutcome.SUCCESS);
                return payload;
              });
    } catch (ResourceAccessException exception) {
      Throwable cause = exception;
      while (cause != null) {
        if (cause instanceof java.net.http.HttpTimeoutException
            || cause instanceof java.net.SocketTimeoutException) {
          outcome.set(ApiOutcome.TIMEOUT);
          throw new CharacterException(CharacterErrorCode.LOST_ARK_API_TIMEOUT);
        }
        cause = cause.getCause();
      }
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    } catch (RestClientException | JacksonException exception) {
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    } finally {
      gate.release();
      metrics.finishApi(sample, endpoint, outcome.get());
    }
  }
}
