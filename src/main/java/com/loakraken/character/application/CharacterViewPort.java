package com.loakraken.character.application;

import java.time.LocalDate;
import java.util.Map;

public interface CharacterViewPort {
  void increment(String characterName, LocalDate date);

  /** from~to(포함) 사이 조회가 있었던 날만 돌려준다. */
  Map<LocalDate, Long> daily(String characterName, LocalDate from, LocalDate to);

  long total(String characterName);
}
