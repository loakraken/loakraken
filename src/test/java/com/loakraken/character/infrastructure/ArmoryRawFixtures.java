package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 테스트용: 공식 armories 원문 JSON → 도메인 장비·보석(실제 수집 경로와 같은 변환). */
public final class ArmoryRawFixtures {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ArmoryRawFixtures() {}

  /**
   * 크라켄 점수 표본(kraken-score/*.json)의 원문은 점수 엔진이 읽는 필드만 남겨 보석에 Name이 없다. 실제 공식 응답처럼 이름을 채운다: 같은 칸 효과
   * 문구에 '재사용 대기시간'이 있으면 'N레벨 작열의 보석', 그 밖 'N레벨 겁화의 보석'(4티어). 이름이 있으면 그대로 둔다.
   */
  public static CharacterLoadout loadout(JsonNode raw) {
    var loadout = MAPPER.treeToValue(raw, LostArkArmoryResponse.class).toLoadout();
    var gems = loadout.gems();
    if (gems == null || gems.effects() == null) return loadout;
    var named = new ArrayList<CharacterLoadout.Gem>();
    for (var gem : gems.items()) {
      if (gem == null || gem.name() != null || gem.level() == null) {
        named.add(gem);
        continue;
      }
      boolean cooldown = false;
      for (var skill : gems.effects().skills())
        if (skill != null && gem.slot() != null && gem.slot().equals(skill.gemSlot()))
          cooldown = String.join(" ", skill.description()).contains("재사용 대기시간");
      named.add(
          new CharacterLoadout.Gem(
              gem.slot(),
              gem.level() + "레벨 " + (cooldown ? "작열" : "겁화") + "의 보석",
              gem.icon(),
              gem.level(),
              gem.grade(),
              gem.extraLines(),
              gem.titleLine(),
              gem.itemLevel(),
              4,
              gem.tradeLines()));
    }
    return new CharacterLoadout(
        loadout.equipment(),
        loadout.engravings(),
        new CharacterLoadout.Gems(named, gems.effects()),
        loadout.cards(),
        loadout.arkPassive(),
        loadout.arkGrid(),
        loadout.skills(),
        loadout.avatars(),
        loadout.collectibles());
  }
}
