package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** 공개 요약(#145)에 쓰는 저장된 관측. 공식 API를 부르지 않는다. */
public interface CharacterPublicSummaryPort {
  /** characters 행과 최신 장비 요약의 레벨·각성. observedAt은 마지막 관측(검색·수집·원정대 확인 중 가장 늦은 값). */
  record Stored(
      String characterName,
      String serverName,
      String className,
      Integer characterLevel,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String arkPassiveTitle,
      Instant observedAt) {}

  Optional<Stored> stored(String characterName);

  /** 처리정지 목록에 있는가(서버 생략 제외는 모든 서버). */
  boolean excluded(String characterName, String serverName);

  /** 서버와 관계없이 이 이름이 처리정지 목록에 있는가(기록이 없을 때 쓴다). */
  boolean excludedAnyServer(String characterName);
}
