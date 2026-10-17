package com.loakraken.character.application;

import java.math.BigDecimal;
import java.util.List;

/** 캐릭터 이름 자동완성. 우리 DB에 기록된 캐릭터만 찾는다. */
public interface CharacterSuggestPort {
  /** 이름이 prefix로 시작하는 캐릭터를 아이템 레벨 높은 순으로 limit개까지. 처리정지 목록은 뺀다. */
  List<Suggestion> suggest(String prefix, int limit);

  record Suggestion(
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      String imageUrl) {}
}
