package com.loakraken.receipt.application;

import com.loakraken.character.domain.CharacterLoadout;
import java.time.Instant;
import java.util.Optional;

/**
 * 영수증 계산에 쓰는 캐릭터별 입력(장비·보석·아크 그리드만)을 둔다. 같은 원정대를 다시 열 때 공식 호출 없이 다시 쓰려는 것이다(보관 시간은 설정, 처리방침의 임시 보관
 * 시간 안). 공식 원문은 두지 않는다. 처리정지 목록 캐릭터는 저장하지 않고, 목록에 들기 전 것은 지운다.
 */
public interface ReceiptInputStore {
  /** observedAt: 이 장비를 관측한 시각. */
  record Input(CharacterLoadout loadout, Instant observedAt) {}

  Optional<Input> find(String characterName);

  void save(String characterName, Input input);

  /** 처리정지 목록에 든 캐릭터의 입력을 지운다(목록에 들기 전에 저장된 것). */
  void delete(java.util.Collection<String> characterNames);
}
