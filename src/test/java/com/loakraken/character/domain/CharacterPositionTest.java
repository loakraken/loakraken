package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CharacterPositionTest {
  @ParameterizedTest
  @CsvSource({
    "바드, 깨달음 2티어 절실한 구원 Lv.3, 절실한 구원",
    "도화가, 깨달음 2티어 만개 Lv.3, 만개",
    "홀리나이트, 깨달음 2티어 축복의 오라 Lv.3, 축복의 오라",
    "발키리, 깨달음 1티어 해방자 Lv.1, 해방자"
  })
  void 서포터_노드가_있으면_티어와_무관하게_서포터다(String className, String node, String evidence) {
    var position = CharacterPosition.judge(className, passive("깨달음 1티어 완벽한 화음 Lv.3", node));
    assertThat(position.role()).isEqualTo(CharacterPosition.Role.SUPPORT);
    assertThat(position.evidence()).isEqualTo(evidence);
  }

  @ParameterizedTest
  @CsvSource({
    "바드, 깨달음 1티어 진실된 용맹 Lv.1",
    "홀리나이트, 깨달음 1티어 심판자 Lv.1",
    "도화가, 깨달음 1티어 회귀 Lv.1",
    "발키리, 깨달음 1티어 빛의 기사 Lv.1"
  })
  void 서포터_가능_직업의_딜러_노드는_딜러다(String className, String node) {
    var position = CharacterPosition.judge(className, passive(node));
    assertThat(position.role()).isEqualTo(CharacterPosition.Role.DEALER);
    assertThat(node).contains(position.evidence());
  }

  @Test
  void 서포터가_없는_직업은_아크패시브와_무관하게_딜러다() {
    var position = CharacterPosition.judge("블레이드", null);
    assertThat(position.role()).isEqualTo(CharacterPosition.Role.DEALER);
    assertThat(position.evidence()).isEqualTo("블레이드");
  }

  @Test
  void 근거가_없으면_추정하지_않고_null이다() {
    assertThat(CharacterPosition.judge("바드", null).role()).isNull();
    assertThat(CharacterPosition.judge("바드", passive("깨달음 1티어 완벽한 화음 Lv.3")).role()).isNull();
    assertThat(CharacterPosition.judge("바드", passive("진화 1티어 절실한 구원 Lv.1")).role()).isNull();
    assertThat(CharacterPosition.judge("바드", passive((String) null)).role()).isNull();
    assertThat(CharacterPosition.judge(null, passive("깨달음 2티어 만개 Lv.3")).role()).isNull();
  }

  @Test
  void 다른_서포터_직업의_노드는_판정에_쓰지_않는다() {
    assertThat(CharacterPosition.judge("바드", passive("깨달음 2티어 만개 Lv.3")).role()).isNull();
  }

  @Test
  void 분석_결과에_직업명을_넘기면_포지션을_판정한다() {
    var loadout =
        new CharacterLoadout(List.of(), null, null, null, passive("깨달음 2티어 만개 Lv.3"), null);
    assertThat(CharacterAnalysis.from(loadout, "도화가").position().role())
        .isEqualTo(CharacterPosition.Role.SUPPORT);
    assertThat(CharacterAnalysis.from(loadout).position().role()).isNull();
  }

  private CharacterLoadout.ArkPassive passive(String... descriptions) {
    return new CharacterLoadout.ArkPassive(
        "아크패시브",
        true,
        List.of(),
        Arrays.stream(descriptions)
            .map(description -> new CharacterLoadout.PassiveEffect("깨달음", description, null))
            .toList());
  }
}
