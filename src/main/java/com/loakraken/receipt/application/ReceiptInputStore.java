package com.loakraken.receipt.application;

import com.loakraken.character.domain.CharacterLoadout;
import java.time.Instant;
import java.util.Optional;

/**
 * 영수증 계산에 쓰는 캐릭터별 입력(장비·보석·아크 그리드만)을 캐릭터 캐시보다 오래 둔다. 같은 원정대를 다시 열 때 공식 호출 없이 다시 쓰려는 것이다.
 * 공식 원문은 두지 않는다.
 */
public interface ReceiptInputStore {
  /** observedAt: 이 장비를 관측한 시각. */
  record Input(CharacterLoadout loadout, Instant observedAt) {}

  Optional<Input> find(String characterName);

  void save(String characterName, Input input);
}
