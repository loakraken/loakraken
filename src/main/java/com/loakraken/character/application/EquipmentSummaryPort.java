package com.loakraken.character.application;

import com.loakraken.character.domain.EquipmentSummary;
import java.time.Instant;

/** 장비 요약 기록(#146). 같은 캐릭터의 최신 행과 특성·전투력이 같으면 새 행을 만들지 않는다. */
public interface EquipmentSummaryPort {
  /** 결과: 새 행(CREATED), 마지막 확인 시각만 갱신(CONFIRMED), 캐릭터 행 없음(SKIPPED). */
  Outcome record(
      String serverName, String characterName, EquipmentSummary summary, Instant observedAt);

  enum Outcome {
    CREATED,
    CONFIRMED,
    SKIPPED
  }

  /** 저장할 때와 같은 정렬 직렬화(features JSON). 캐시로 찾은 캐릭터도 저장 행과 같은 모양으로 읽게 한다. */
  String canonicalFeatures(EquipmentSummary summary);

  /**
   * 이름의 마지막 장비 요약(캐릭터명은 전체 서버에서 하나, 여러 행이면 가장 최근). 처리정지 목록 캐릭터는 없는 것과 같다. itemLevel·observedAt은
   * characters 행 값(통계 모집단과 같은 열: item_level, profile_refreshed_at)이다.
   */
  java.util.Optional<Stored> latest(String characterName);

  record Stored(
      String characterName,
      String serverName,
      String className,
      java.math.BigDecimal itemLevel,
      java.math.BigDecimal combatPower,
      String arkPassiveTitle,
      Instant observedAt,
      String featuresJson) {}
}
