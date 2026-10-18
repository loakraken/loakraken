package com.loakraken.character.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loakraken.character.domain.CharacterAnalysis;
import com.loakraken.character.domain.CharacterDetails;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * refreshAvailableAt은 캐시 만료 예정 시각이다. 이전 캐시 값처럼 알 수 없으면 null이다. krakenScoreInput은 공식 armories에서 만든
 * 크라켄 점수 엔진 입력(#559, 원문이 아니라 엔진이 읽는 값만)이고, arkGridReal은 그 입력으로 조회 때 한 번 계산한 아크 그리드 젬 실 효율(#520)이다.
 * 옛 캐시이거나 만들지 못했으면 둘 다 null이다.
 */
public record CharacterResult(
    CharacterDetails details,
    Instant fetchedAt,
    Instant refreshAvailableAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode krakenScoreInput,
    @JsonInclude(JsonInclude.Include.NON_NULL) ArkGridRealEfficiency arkGridReal) {
  public CharacterResult {
    // 캐시에서 JSON null을 읽으면 NullNode가 된다 — 없음은 늘 null로 둔다(저장·복원 뒤에도 같은 값).
    if (krakenScoreInput != null && (krakenScoreInput.isNull() || krakenScoreInput.isMissingNode()))
      krakenScoreInput = null;
  }

  public CharacterResult(
      CharacterDetails details,
      Instant fetchedAt,
      Instant refreshAvailableAt,
      JsonNode krakenScoreInput) {
    this(details, fetchedAt, refreshAvailableAt, krakenScoreInput, null);
  }

  public CharacterResult(CharacterDetails details, Instant fetchedAt, Instant refreshAvailableAt) {
    this(details, fetchedAt, refreshAvailableAt, null, null);
  }

  public CharacterResult(CharacterDetails details, Instant fetchedAt) {
    this(details, fetchedAt, null, null, null);
  }

  public CharacterAnalysis analysis() {
    return CharacterAnalysis.from(details.loadout(), details.profile().className());
  }
}
