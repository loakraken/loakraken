package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LostArkArkGridTooltipTest {
  // 2026-10-08 공식 아크 그리드 코어 Tooltip 구조(문구 축약)
  private static final String CORE =
      """
      {"Element_000":{"type":"NameTagBox","value":"질서의 해 코어 : 살상연희"},
       "Element_003":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>코어 타입</FONT>","Element_001":"질서 - 해"}},
       "Element_004":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>코어 공급 의지력</FONT>","Element_001":"<FONT COLOR = '#B7FB00'>15</FONT> 포인트"}},
       "Element_005":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>코어 옵션</FONT>","Element_001":"<FONT color='#FFD200'>[14P]</FONT> 효과 B<BR>보충 설명<br><FONT color='#FFD200'>[10P]</FONT> 일반 스킬 피해량이 <FONT COLOR='#99ff99'>3.0%</FONT> 증가한다.<br><FONT color='#FFD200'>[20P]</FONT> 효과 D"}},
       "Element_006":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>코어 옵션 발동 조건</FONT>","Element_001":"<img src='emoticon_sign_greenDot' width='0' height='0' vspace='-3'></img>버스트 전용<br><img src='x'></img>아크 패시브 4티어 혼돈의 힘 활성화 필요"}},
       "Element_007":null}
      """;

  private static final String GEM =
      """
      {"Element_000":{"type":"NameTagBox","value":"<P ALIGN='CENTER'><FONT COLOR='#F99200'>질서의 젬 : 안정</FONT></P>"},
       "Element_004":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>젬 기본 정보</FONT>","Element_001":"젬 타입 : 질서<br>젬 포인트 : <FONT COLOR = '#B7FB00'>11</FONT>"}},
       "Element_005":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>젬 효과</FONT>","Element_001":"<img src='emoticon_arkgrid_willpower'></img>필요 의지력 : <FONT color='#FFD200'>3</FONT> (기본 값 8 – 의지력 효율 <FONT COLOR = '#FBB29C'>5</FONT>)<br><img src='emoticon_arkgrid_corepoint'></img>질서 포인트 : <FONT COLOR = '#B7FB00'>4</FONT><br>[공격력] <FONT color='#FFD200'>Lv.1</FONT><br><img src='emoticon_sign_greenDot'></img>공격력 +0.03%<br>[아군 피해 강화] <FONT color='#FFD200'>Lv.1</FONT><br><img src='emoticon_sign_greenDot'></img>아군 피해량 강화 효과 +0.05%"}},
       "Element_007":{"type":"Progress","value":null}}
      """;

  @Test
  void 코어는_타입_공급_의지력_구간_옵션_발동_조건을_읽는다() {
    var core = LostArkArkGridTooltip.core(CORE);
    assertThat(core.coreType()).isEqualTo("질서 - 해");
    assertThat(core.supplyWillpower()).isEqualTo(15);
    assertThat(core.options())
        .extracting(CharacterLoadout.CoreOption::threshold)
        .containsExactly(10, 14, 20);
    assertThat(core.options().getFirst().text()).isEqualTo("일반 스킬 피해량이 3.0% 증가한다.");
    assertThat(core.options().get(1).text()).isEqualTo("효과 B 보충 설명");
    assertThat(core.conditions()).containsExactly("버스트 전용", "아크 패시브 4티어 혼돈의 힘 활성화 필요");
  }

  @Test
  void 젬은_포인트_필요_의지력_옵션_레벨과_수치를_읽는다() {
    var gem = LostArkArkGridTooltip.gem(GEM);
    assertThat(gem.name()).isEqualTo("질서의 젬 : 안정");
    assertThat(gem.gemType()).isEqualTo("질서");
    assertThat(gem.gemPoint()).isEqualTo(11);
    assertThat(gem.requiredWillpower()).isEqualTo(3);
    assertThat(gem.baseWillpower()).isEqualTo(8);
    assertThat(gem.willpowerEfficiency()).isEqualTo(5);
    assertThat(gem.corePoint()).isEqualTo(4);
    assertThat(gem.options()).hasSize(2);
    assertThat(gem.options().getFirst().name()).isEqualTo("공격력");
    assertThat(gem.options().getFirst().level()).isEqualTo(1);
    assertThat(gem.options().getFirst().effect()).isEqualTo("공격력 +0.03%");
    assertThat(gem.options().getFirst().percent()).isEqualByComparingTo("0.03");
    assertThat(gem.options().get(1).percent()).isEqualByComparingTo("0.05");
  }

  @Test
  void 효과_Tooltip은_문구와_수치로_나눈다() {
    var effect = LostArkArkGridTooltip.effect("공격력", 28, "<FONT COLOR='#FFFFFF'>공격력 +1.02%</FONT>");
    assertThat(effect.value()).isEqualTo("공격력 +1.02%");
    assertThat(effect.percent()).isEqualByComparingTo("1.02");
    assertThat(LostArkArkGridTooltip.effect("공격력", 1, null).value()).isNull();
    assertThat(LostArkArkGridTooltip.effect("공격력", 1, "unparsed-secret-tooltip").value()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "unparsed-secret-tooltip", "[1,2]", "{\"Element_000\":"})
  void 해석할_수_없는_Tooltip은_null이다(String tooltip) {
    assertThat(LostArkArkGridTooltip.core(tooltip)).isNull();
    assertThat(LostArkArkGridTooltip.gem(tooltip)).isNull();
  }
}
