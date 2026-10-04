package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LostArkEquipmentTooltipTest {
  private static final String ARMOR =
      """
      {"Element_000":{"type":"NameTagBox","value":"<P ALIGN='CENTER'><FONT COLOR='#E3C7A1'>+19 테스트 머리장식</FONT></P>"},
       "Element_001":{"type":"ItemTitle","value":{"leftStr0":"<FONT SIZE='12'>고대 머리 방어구</FONT>",
         "leftStr2":"<FONT SIZE='14'>아이템 레벨 1770 (티어 4)</FONT>","qualityValue":95,"rightStr0":"장착중"}},
       "Element_002":{"type":"SingleTextBox","value":"캐릭터 귀속됨"},
       "Element_003":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>기본 효과</FONT>",
         "Element_001":"물리 방어력 +9734<BR>민첩 +120150"}},
       "Element_004":null,
       "Element_005":{"type":"ItemPartBox","value":{"Element_000":"추가 효과","Element_001":"생명 활성력 +1264"}},
       "Element_006":{"type":"Progress","value":{"title":"현재 단계 재련 경험치","value":0,"maximum":67000}},
       "Element_007":{"type":"ItemPartBox","value":{"Element_000":"아크 패시브 포인트 효과","Element_001":"진화 +24"}},
       "Element_008":{"type":"FutureElement","value":{"unknown":true}}}
      """;

  @Test
  void 방어구의_재련_품질_레벨_티어_포인트와_효과를_해석한다() {
    var detail = LostArkEquipmentTooltip.parse("+19 테스트 머리장식", ARMOR);
    assertThat(detail.refineLevel()).isEqualTo(19);
    assertThat(detail.quality()).isEqualTo(95);
    assertThat(detail.itemLevel()).isEqualTo(1770);
    assertThat(detail.tier()).isEqualTo(4);
    assertThat(detail.arkPassivePoint()).isEqualTo(new CharacterLoadout.PointBonus("진화", 24));
    assertThat(detail.effects())
        .containsExactly(
            new CharacterLoadout.EffectGroup(
                "기본 효과", java.util.List.of("물리 방어력 +9734", "민첩 +120150")),
            new CharacterLoadout.EffectGroup("추가 효과", java.util.List.of("생명 활성력 +1264")));
    assertThat(detail.stoneEngravings()).isEmpty();
  }

  @Test
  void 장신구는_아이템_레벨_없이_티어와_연마_효과를_해석한다() {
    var detail =
        LostArkEquipmentTooltip.parse(
            "테스트 목걸이",
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr2":"아이템 티어 4","qualityValue":87}},
             "Element_006":{"type":"ItemPartBox","value":{"Element_000":"연마 효과",
               "Element_001":"추가 피해 +2.60%<BR>낙인력 +2.15%"}},
             "Element_007":{"type":"ItemPartBox","value":{"Element_000":"아크 패시브 포인트 효과","Element_001":"깨달음 +13"}}}
            """);
    assertThat(detail.refineLevel()).isNull();
    assertThat(detail.quality()).isEqualTo(87);
    assertThat(detail.itemLevel()).isNull();
    assertThat(detail.tier()).isEqualTo(4);
    assertThat(detail.arkPassivePoint()).isEqualTo(new CharacterLoadout.PointBonus("깨달음", 13));
    assertThat(detail.effects().getFirst().lines()).containsExactly("추가 피해 +2.60%", "낙인력 +2.15%");
  }

  @Test
  void 연마_효과는_공식_수치_색으로_등급을_정하고_모르는_색은_null로_둔다() {
    var detail =
        LostArkEquipmentTooltip.parse(
            "테스트 목걸이",
            """
            {"Element_006":{"type":"ItemPartBox","value":{"Element_000":"연마 효과",
              "Element_001":"<img src='emoticon_sign_greenDot' width='0' height='0' vspace='-3'></img>추가 피해 <FONT COLOR='FE9600'>+2.60%</FONT><BR><img src='x'></img>무기 공격력 <FONT COLOR='#CE43FC'>+1.80%</FONT><BR>낙인력 <font color='00b5ff'>+2.15%</font><BR>공격력 <FONT COLOR='123456'>+80</FONT><BR>설명만 있는 줄"}}}
            """);
    assertThat(detail.polishEffects())
        .containsExactly(
            new CharacterLoadout.PolishEffect("추가 피해", "+2.60%", CharacterLoadout.PolishGrade.HIGH),
            new CharacterLoadout.PolishEffect("무기 공격력", "+1.80%", CharacterLoadout.PolishGrade.MID),
            new CharacterLoadout.PolishEffect("낙인력", "+2.15%", CharacterLoadout.PolishGrade.LOW),
            new CharacterLoadout.PolishEffect("공격력", "+80", null));
    assertThat(detail.effects().getFirst().lines())
        .containsExactly("추가 피해 +2.60%", "무기 공격력 +1.80%", "낙인력 +2.15%", "공격력 +80", "설명만 있는 줄");
  }

  @Test
  void 연마_효과가_아닌_효과_묶음은_등급을_만들지_않는다() {
    var detail =
        LostArkEquipmentTooltip.parse(
            "테스트 팔찌",
            """
            {"Element_005":{"type":"ItemPartBox","value":{"Element_000":"팔찌 효과",
              "Element_001":"치명타 피해 <FONT COLOR='#CE43FC'>+8.40%</FONT>"}}}
            """);
    assertThat(detail.polishEffects()).isEmpty();
  }

  @Test
  void 품질이_적용되지_않는_장비는_품질을_null로_둔다() {
    var detail =
        LostArkEquipmentTooltip.parse(
            "테스트 팔찌",
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr2":"아이템 티어 4","qualityValue":-1}},
             "Element_005":{"type":"ItemPartBox","value":{"Element_000":"팔찌 효과",
               "Element_001":"특화 +81<BR>추가 피해가 2.5% 증가한다."}}}
            """);
    assertThat(detail.quality()).isNull();
    assertThat(detail.effects().getFirst().title()).isEqualTo("팔찌 효과");
  }

  @Test
  void 어빌리티_스톤의_무작위_각인과_보너스_줄을_해석한다() {
    var detail =
        LostArkEquipmentTooltip.parse(
            "테스트 스톤",
            """
            {"Element_007":{"type":"IndentStringGroup","value":{"Element_000":{"topStr":"<FONT>무작위 각인 효과</FONT>",
              "contentStr":{"Element_000":{"contentStr":"[<FONT COLOR='#FFFFAC'>아드레날린</FONT>] Lv.3","bPoint":0},
                "Element_001":{"contentStr":"[원한] Lv.2"},
                "Element_002":{"contentStr":"<FONT COLOR='#FE2E2E'>[방어력 감소]</FONT> Lv.0"},
                "Element_003":{"contentStr":"[레벨 보너스] 기본 공격력 +1.50%"}}}}}}
            """);
    assertThat(detail.stoneEngravings())
        .containsExactly(
            new CharacterLoadout.StoneEngraving("아드레날린", 3),
            new CharacterLoadout.StoneEngraving("원한", 2),
            new CharacterLoadout.StoneEngraving("방어력 감소", 0));
    assertThat(detail.effects().getFirst().lines()).hasSize(4).contains("[레벨 보너스] 기본 공격력 +1.50%");
  }

  @Test
  void 제목_요소가_여러_개면_첫_번째만_사용한다() {
    var detail =
        LostArkEquipmentTooltip.parse(
            "+10 장비",
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr2":"아이템 레벨 1700 (티어 4)","qualityValue":90}},
             "Element_002":{"type":"ItemTitle","value":{"leftStr2":"","qualityValue":-1}}}
            """);
    assertThat(detail.quality()).isEqualTo(90);
    assertThat(detail.itemLevel()).isEqualTo(1700);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "  ", "unparsed-secret-tooltip", "[1,2]", "\"text\"", "{\"Element_000\":"})
  void Tooltip이_없거나_해석할_수_없으면_null이다(String tooltip) {
    assertThat(LostArkEquipmentTooltip.parse("+10 장비", tooltip)).isNull();
    assertThat(LostArkEquipmentTooltip.parse("+10 장비", null)).isNull();
  }

  @Test
  void 예상과_다른_값은_추정하지_않고_개별_필드만_null로_둔다() {
    var detail =
        LostArkEquipmentTooltip.parse(
            "+120 장비",
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr2":"아이템 레벨 알 수 없음","qualityValue":"95"}},
             "Element_002":{"type":"ItemTitle","value":"문자열 값"},
             "Element_003":{"type":"ItemPartBox","value":{"Element_000":"아크 패시브 포인트 효과",
               "Element_001":"진화 +24<BR>깨달음 +10"}},
             "Element_004":{"type":"ItemPartBox","value":null},
             "Element_005":{"value":{"Element_000":"타입 없음"}}}
            """);
    assertThat(detail.refineLevel()).isNull();
    assertThat(detail.quality()).isNull();
    assertThat(detail.itemLevel()).isNull();
    assertThat(detail.tier()).isNull();
    assertThat(detail.arkPassivePoint()).isNull();
    assertThat(detail.effects()).isEmpty();
  }
}
