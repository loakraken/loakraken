package com.loakraken.character.application;

import java.util.List;

/** 공식 전투정보실 캐릭터 페이지에서 필요한 값만 뽑는다(#531·#532). 원문은 돌려주지도 남기지도 않는다. */
public interface ArmoryPagePort {
  /**
   * 캐릭터 페이지를 읽는다.
   *
   * @throws ArmoryPageFetchException 요청·해석 실패. 종류는 {@link ArmoryPageFetchException.Kind}
   */
  Page fetch(String characterName);

  /**
   * 페이지의 스킬 코드 버튼이 부르는 요청으로 128자 스킬 코드를 받는다(#532). 쿠키·로그인·토큰을 요구하면 흉내 내지 않고 ACCESS_DENIED로
   * 실패한다.
   *
   * @throws ArmoryPageFetchException 요청 실패·거절·형식 불일치
   */
  String skillCode(Identity identity);

  /** 생활 스킬 한 줄. 레벨을 못 읽으면 null. */
  record LifeSkill(String name, Integer level) {}

  /**
   * 페이지 스크립트의 캐릭터 식별 값 3개(#532). 계정을 가리키는 값이라 <b>메모리에서만</b> 쓰고 로그·캐시·응답·지표에 남기지 않는다. toString도
   * 값을 숨긴다.
   */
  record Identity(String memberNo, String worldNo, String pcId) {
    @Override
    public String toString() {
      return "Identity[hidden]";
    }
  }

  /** 페이지에서 뽑은 값. lifeSkills는 공식 순서, identity는 못 읽으면 null. */
  record Page(List<LifeSkill> lifeSkills, Identity identity) {
    public Page {
      lifeSkills = List.copyOf(lifeSkills);
    }

    public Page(List<LifeSkill> lifeSkills) {
      this(lifeSkills, null);
    }

    @Override
    public String toString() {
      return "Page[lifeSkills=" + lifeSkills + ", identity=" + (identity == null ? "none" : "hidden") + "]";
    }
  }
}
