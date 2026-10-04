package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterSiblingsResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "원정대 캐릭터 조회 결과. 조회한 캐릭터 자신을 포함합니다.")
public record CharacterSiblingsResponse(
    @Schema(description = "요청한 캐릭터명(앞뒤 공백 제거·NFC 정규화)") String characterName,
    List<CharacterResponse.SiblingResponse> siblings,
    @Schema(description = "외부 API에서 원정대를 실제 수집한 UTC 시각. 캐시 조회에서도 유지됩니다.") Instant fetchedAt) {
  static CharacterSiblingsResponse from(CharacterSiblingsResult result) {
    return new CharacterSiblingsResponse(
        result.characterName(),
        CharacterResponse.SiblingResponse.from(result.siblings()),
        result.fetchedAt());
  }
}
