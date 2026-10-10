package com.loakraken.character.domain;

import java.util.Collection;

/** 원천 응답 구조의 제공 상태이며 장착 여부나 데이터의 유효성을 뜻하지 않는다. */
public enum ObservationState {
  PROVIDED,
  PROVIDED_EMPTY,
  NOT_PROVIDED;

  public static ObservationState collection(Collection<?> value) {
    return value == null ? NOT_PROVIDED : value.isEmpty() ? PROVIDED_EMPTY : PROVIDED;
  }
}
