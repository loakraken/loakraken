package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterSiblingsResult;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "원정대 캐릭터 조회 결과. 조회한 캐릭터 자신을 포함합니다.")
public record CharacterSiblingsResponse(
    @Schema(description = "요청한 캐릭터명(앞뒤 공백 제거·NFC 정규화)") String characterName,
    List<CharacterResponse.SiblingResponse> siblings,
    @Schema(description = "외부 API에서 원정대를 실제 수집한 UTC 시각. 캐시 조회에서도 유지됩니다.") Instant fetchedAt,
    @Schema(
            description =
                "캐시 만료 예정 UTC 시각. 이 시각 이전 요청은 캐시를 반환해 fetchedAt이 바뀌지 않습니다. 캐시 적중에서도 유지되며 실제 만료는 1초 미만 늦을 수 있습니다. 알 수 없으면 null입니다.")
        @NullableResponse
        Instant refreshAvailableAt) {
  static CharacterSiblingsResponse from(CharacterSiblingsResult result) {
    return new CharacterSiblingsResponse(
        result.characterName(),
        CharacterResponse.SiblingResponse.from(result.siblings()),
        result.fetchedAt(),
        result.refreshAvailableAt());
  }
}
