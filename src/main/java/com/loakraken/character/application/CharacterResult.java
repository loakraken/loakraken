package com.loakraken.character.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loakraken.character.domain.CharacterAnalysis;
import com.loakraken.character.domain.CharacterDetails;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * refreshAvailableAt은 캐시 만료 예정 시각이다. 이전 캐시 값처럼 알 수 없으면 null이다. krakenScoreInput은 공식 armories에서 만든
 * 크라켄 점수 엔진 입력(#559, 원문이 아니라 엔진이 읽는 값만)이고, arkGridReal은 그 입력으로 조회 때 한 번 계산한 아크 그리드 젬 실 효율(#520)이다.
 * 옛 캐시이거나 만들지 못했으면 둘 다 null이다.
 *
 * <p>shapeVerdict는 조회 때 남긴 2부 대비 원본 형태 판정(어긋난 HARD 규칙 id, 빈 목록 = 오늘의 모양). 판정이 생기기 전 캐시·검사하지 못한 조회는
 * null이고 막지 않는다. 막을지는 읽을 때 스위치로 정한다({@link CombatPowerGuard}).
 */
public record CharacterResult(
    CharacterDetails details,
    Instant fetchedAt,
    Instant refreshAvailableAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode krakenScoreInput,
    @JsonInclude(JsonInclude.Include.NON_NULL) ArkGridRealEfficiency arkGridReal,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<String> shapeVerdict) {
  public CharacterResult {
    // 캐시에서 JSON null을 읽으면 NullNode가 된다 — 없음은 늘 null로 둔다(저장·복원 뒤에도 같은 값).
    if (krakenScoreInput != null && (krakenScoreInput.isNull() || krakenScoreInput.isMissingNode()))
      krakenScoreInput = null;
    if (shapeVerdict != null) shapeVerdict = List.copyOf(shapeVerdict);
  }

  public CharacterResult(
      CharacterDetails details,
      Instant fetchedAt,
      Instant refreshAvailableAt,
      JsonNode krakenScoreInput,
      ArkGridRealEfficiency arkGridReal) {
    this(details, fetchedAt, refreshAvailableAt, krakenScoreInput, arkGridReal, null);
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
