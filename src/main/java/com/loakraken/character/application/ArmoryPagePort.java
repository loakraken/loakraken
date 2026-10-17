package com.loakraken.character.application;

import java.util.List;

/** 공식 전투정보실 캐릭터 페이지에서 필요한 값만 뽑는다(#531). 원문은 돌려주지도 남기지도 않는다. */
public interface ArmoryPagePort {
  /**
   * @throws ArmoryPageFetchException 요청·해석 실패. 종류는 {@link ArmoryPageFetchException.Kind}
   */
  Page fetch(String characterName);

  /** 생활 스킬 한 줄. 레벨을 못 읽으면 null. */
  record LifeSkill(String name, Integer level) {}

  /** 페이지에서 뽑은 값. lifeSkills는 공식 순서. */
  record Page(List<LifeSkill> lifeSkills) {
    public Page {
      lifeSkills = List.copyOf(lifeSkills);
    }
  }
}
