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
}
