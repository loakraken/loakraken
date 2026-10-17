package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import java.util.List;

public interface CharacterDetailsPort {
  FetchedCharacter fetch(String characterName);

  FetchedCharacter fetchArmory(String characterName);

  List<CharacterSibling> fetchSiblings(String characterName);

  /**
   * 공식 프로필만(전투력 포함, 원정대 전투력 불러오기 L19). 공식 호출은 사용자 몫 reserve를 남기는 쪽으로만 한다. 없는 캐릭터면
   * CHARACTER_NOT_FOUND.
   */
  default CharacterProfile fetchProfile(String characterName, int reserve) {
    return fetchArmory(characterName).details().profile();
  }
}
