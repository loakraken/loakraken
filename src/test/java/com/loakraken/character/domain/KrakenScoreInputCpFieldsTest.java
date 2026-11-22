package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 절대 전투력 경로 입력(계산 검증 11-21 PR1 명세): 전투 레벨, 무기 재련 단계, 카드 세트 문자열, 펫·음식 기본값. 크라켄 점수에는 쓰지 않는다(점수 경로가 읽지
 * 않는 키).
 */
class KrakenScoreInputCpFieldsTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void 카드_세트는_묶음마다_마지막_각성합계_줄을_이어_쓴다() {
    // 실제 응답 모양(묶음 두 개, 각성합계 줄은 오름차순): 묶음 순서대로 잇는다.
    var raw =
        raw(
            """
            {"ArmoryCard":{"Effects":[
              {"Index":0,"Items":[{"Name":"세 우마르가 오리라 3세트"},{"Name":"세 우마르가 오리라 3세트 (6각성합계)"},
                {"Name":"세 우마르가 오리라 3세트 (9각성합계)"},{"Name":"세 우마르가 오리라 3세트 (15각성합계)"}]},
              {"Index":1,"Items":[{"Name":"라제니스의 운명 2세트"},{"Name":"라제니스의 운명 2세트 (4각성합계)"},
                {"Name":"라제니스의 운명 2세트 (6각성합계)"},{"Name":"라제니스의 운명 2세트 (10각성합계)"}]}]}}
            """);
    assertThat(KrakenScoreInputMapper.cardSets(raw)).isEqualTo("세 우마르가 오리라 15각 + 라제니스의 운명 10각");
    assertThat(KrakenScoreInputMapper.from(raw).path("card").asString())
        .isEqualTo("세 우마르가 오리라 15각 + 라제니스의 운명 10각");
    var single =
        raw(
            """
            {"ArmoryCard":{"Effects":[{"Index":0,"Items":[{"Name":"세상을 구하는 빛 2세트"},
              {"Name":"세상을 구하는 빛 6세트"},{"Name":"세상을 구하는 빛 6세트 (12각성합계)"},
              {"Name":"세상을 구하는 빛 6세트 (30각성합계)"}]}]}}
            """);
    assertThat(KrakenScoreInputMapper.cardSets(single)).isEqualTo("세상을 구하는 빛 30각");
    // 각성합계 줄이 없는 묶음은 빼고, 하나도 없으면 null.
    var none =
        raw("{\"ArmoryCard\":{\"Effects\":[{\"Index\":0,\"Items\":[{\"Name\":\"어느 세트 2세트\"}]}]}}");
    assertThat(KrakenScoreInputMapper.cardSets(none)).isNull();
    assertThat(KrakenScoreInputMapper.from(none).path("card").isNull()).isTrue();
    assertThat(KrakenScoreInputMapper.from(raw("{}")).path("card").isNull()).isTrue();
  }

  @Test
  void 무기_재련_단계는_이름_앞_더하기_수이고_없으면_0이다() {
    assertThat(KrakenScoreInputMapper.reforge("+10 호왕 : 의")).isEqualTo(10);
    assertThat(KrakenScoreInputMapper.reforge("+19 운명의 전율 검")).isEqualTo(19);
    assertThat(KrakenScoreInputMapper.reforge("운명의 전율 검")).isZero();
    assertThat(KrakenScoreInputMapper.reforge("")).isZero();
    // 너무 긴 수는 깨진 이름으로 보고 0(정수 변환이 터지면 점수 입력 전체가 비므로, 코드 리뷰 #1027 P3). 태그는 뗀다.
    assertThat(KrakenScoreInputMapper.reforge("+99999999999 깨진 무기")).isZero();
    assertThat(KrakenScoreInputMapper.reforge("<FONT COLOR='#E3C7A1'>+10 테스트 무기</FONT>"))
        .isEqualTo(10);
    var raw =
        raw(
            "{\"ArmoryEquipment\":[{\"Type\":\"무기\",\"Name\":\"+25 테스트 무기\",\"Grade\":\"고대\",\"Tooltip\":\"{}\"}]}");
    assertThat(KrakenScoreInputMapper.from(raw).at("/armory/equipment/weapon/reforge").asInt(-1))
        .isEqualTo(25);
  }

  @Test
  void 전투_레벨과_펫_음식_기본값을_넣는다() {
    var input = KrakenScoreInputMapper.from(raw("{\"ArmoryProfile\":{\"CharacterLevel\":70}}"));
    assertThat(input.at("/profile/level").asInt()).isEqualTo(70);
    assertThat(KrakenScoreInputMapper.from(raw("{}")).at("/profile/level").isNull()).isTrue();
    var base = input.path("baseEffect");
    assertThat(base.path("petDamage").asInt()).isEqualTo(1);
    assertThat(base.path("petStat").asInt()).isEqualTo(1);
    assertThat(base.path("petHpEffect").asInt()).isEqualTo(5);
    assertThat(base.path("petHpPatrol").asInt()).isEqualTo(2);
    assertThat(base.path("dinner").asInt()).isZero();
    assertThat(base.path("isAzena").asBoolean(false)).isTrue();
  }

  private static ObjectNode raw(String json) {
    return (ObjectNode) MAPPER.readTree(json);
  }
}
