package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 아이템 hover 툴팁 줄(2026-10-10 공식 armory 형식: 장신구·무기·보석·룬). */
class LostArkTooltipLinesTest {
  @Test
  void 장신구는_등급_부위_줄과_귀속_거래_줄을_나누고_나머지는_원문_순서로_담는다() {
    var lines =
        LostArkTooltipLines.parse(
            """
            {"Element_000":{"type":"NameTagBox","value":"도래한 결전의 귀걸이"},
             "Element_001":{"type":"ItemTitle","value":{"leftStr0":"<FONT SIZE='12'>고대 귀걸이</FONT>","qualityValue":90}},
             "Element_002":{"type":"SingleTextBox","value":"<FONT SIZE='12'><FONT COLOR='#C24B46'>거래 제한 아이템 레벨</FONT> 1680<BR>캐릭터 귀속됨<BR>거래 <FONT COLOR='#FFD200'>3</FONT>회 가능</FONT>"},
             "Element_003":{"type":"MultiTextBox","value":"|거래가능"},
             "Element_004":{"type":"ItemPartBox","value":{"Element_000":"기본 효과","Element_001":"힘 +13765"}},
             "Element_005":null,
             "Element_008":{"type":"Progress","value":null},
             "Element_010":{"type":"SingleTextBox","value":"[카제로스 레이드] 1막<BR>[카제로스 레이드] 2막"}}
            """,
            null);
    assertThat(lines.titleLine()).isEqualTo("고대 귀걸이");
    assertThat(lines.tradeLines())
        .containsExactly("거래 제한 아이템 레벨 1680", "캐릭터 귀속됨", "거래 3회 가능", "거래가능");
    assertThat(lines.extraLines()).containsExactly("[카제로스 레이드] 1막", "[카제로스 레이드] 2막");
    assertThat(lines.refineExp()).isNull();
  }

  @Test
  void 장비는_재련_경험치_막대와_내구도를_읽는다() {
    var lines =
        LostArkTooltipLines.parse(
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr0":"고대 검"}},
             "Element_002":{"type":"SingleTextBox","value":"캐릭터 귀속됨"},
             "Element_003":{"type":"MultiTextBox","value":"|거래 불가"},
             "Element_007":{"type":"Progress","value":{"forceValue":" ","maximum":112000,"minimum":0,"title":"<FONT COLOR='#A9D0F5'>현재 단계 재련 경험치</FONT>","value":0}},
             "Element_008":{"type":"SingleTextBox","value":"분해불가, 품질 업그레이드 불가"},
             "Element_012":{"type":"ShowMeTheMoney","value":"<FONT SIZE='12'>내구도 <FONT COLOR='#FFFFFF'>62 / 175</FONT></FONT>|"}}
            """,
            null);
    assertThat(lines.refineExp().current()).isZero();
    assertThat(lines.refineExp().max()).isEqualTo(112_000);
    assertThat(lines.tradeLines()).containsExactly("캐릭터 귀속됨", "거래 불가");
    assertThat(lines.extraLines()).containsExactly("분해불가, 품질 업그레이드 불가", "내구도 62 / 175");
  }

  /** 계산 검증 10-10 18:10 비극화 투구 형식. */
  @Test
  void 상급_재련_문구는_단계로_읽고_나머지_줄에_넣지_않는다() {
    var lines =
        LostArkTooltipLines.parse(
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr0":"고대 머리 방어구"}},
             "Element_002":{"type":"SingleTextBox","value":"캐릭터 귀속됨"},
             "Element_003":{"type":"MultiTextBox","value":"|거래 불가"},
             "Element_004":{"type":"SingleTextBox","value":"<FONT SIZE='14'><FONT COLOR='#A8EA6C'>[상급 재련]</FONT> <FONT COLOR='#FFD200'>28</FONT>단계</FONT>"},
             "Element_009":{"type":"SingleTextBox","value":"분해불가"}}
            """,
            null);
    assertThat(lines.advancedRefineLevel()).isEqualTo(28);
    assertThat(lines.tradeLines()).containsExactly("캐릭터 귀속됨", "거래 불가");
    assertThat(lines.extraLines()).containsExactly("분해불가");
  }

  @Test
  void 보석의_보석_레벨_줄은_거래_줄이_아니고_룬은_효과_줄을_따로_담는다() {
    var gem =
        LostArkTooltipLines.parse(
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr0":"전설 보석"}},
             "Element_002":{"type":"SingleTextBox","value":"장착, 합성, 조율 가능 아이템 레벨 1640<BR>캐릭터 귀속됨"},
             "Element_003":{"type":"MultiTextBox","value":"|거래 불가"},
             "Element_004":{"type":"SingleTextBox","value":"보석 레벨 6"}}
            """,
            null);
    assertThat(gem.tradeLines()).containsExactly("장착, 합성, 조율 가능 아이템 레벨 1640", "캐릭터 귀속됨", "거래 불가");
    assertThat(gem.extraLines()).containsExactly("보석 레벨 6");
    var rune =
        LostArkTooltipLines.parse(
            """
            {"Element_000":{"type":"NameTagBox","value":"속행"},
             "Element_001":{"type":"ItemTitle","value":{"leftStr0":"전설 스킬 룬"}},
             "Element_003":{"type":"ItemPartBox","value":{"Element_000":"스킬 룬 효과","Element_001":"스킬 사용 시 일정 확률로 전체 재사용 대기 시간이 16% 감소"}},
             "Element_004":{"type":"SingleTextBox","value":"스킬에 강력한 힘을 부여할 수 있는 특별한 룬이다."}}
            """,
            "스킬 룬 효과");
    assertThat(rune.effectLines()).containsExactly("스킬 사용 시 일정 확률로 전체 재사용 대기 시간이 16% 감소");
    assertThat(rune.extraLines()).containsExactly("스킬에 강력한 힘을 부여할 수 있는 특별한 룬이다.");
  }

  @Test
  void 나머지_줄마다_바로_앞_섹션_제목을_같은_길이로_담는다() {
    // #285: 팔찌 "효과 부여 불가"가 "팔찌 효과" 뒤, 아크 패시브 포인트 앞에 있는지 웹이 알 수 있게 한다.
    var lines =
        LostArkTooltipLines.parse(
            """
            {"Element_001":{"type":"ItemTitle","value":{"leftStr0":"고대 팔찌","leftStr2":"아이템 레벨 1640 (티어 4)"}},
             "Element_002":{"type":"SingleTextBox","value":"캐릭터 귀속됨"},
             "Element_003":{"type":"MultiTextBox","value":"|거래 불가"},
             "Element_004":{"type":"SingleTextBox","value":"분해불가"},
             "Element_005":{"type":"ItemPartBox","value":{"Element_000":"팔찌 효과","Element_001":"치명 +100"}},
             "Element_006":{"type":"SingleTextBox","value":"효과 부여 불가"},
             "Element_007":{"type":"IndentStringGroup","value":{"Element_000":{"topStr":"아크 패시브 포인트 효과","contentStr":{}}}},
             "Element_008":{"type":"SingleTextBox","value":"[제작] 대도시"}}
            """,
            null);
    assertThat(lines.extraLines()).containsExactly("분해불가", "효과 부여 불가", "[제작] 대도시");
    assertThat(lines.extraLineSections()).containsExactly("", "팔찌 효과", "아크 패시브 포인트 효과");
    assertThat(lines.itemLevel()).isEqualTo(1640);
    assertThat(lines.tier()).isEqualTo(4);
  }

  @Test
  void 보석은_등급_줄과_아이템_레벨_티어_귀속_거래_줄을_읽는다() {
    var lines =
        LostArkTooltipLines.parse(
            """
            {"Element_000":{"type":"NameTagBox","value":"10레벨 겁화의 보석"},
             "Element_001":{"type":"ItemTitle","value":{"leftStr0":"전설 보석","leftStr2":"아이템 레벨 1640 (티어 4)"}},
             "Element_002":{"type":"SingleTextBox","value":"장착, 합성, 조율 가능 아이템 레벨 1640<BR>캐릭터 귀속됨"},
             "Element_003":{"type":"MultiTextBox","value":"|거래 불가"},
             "Element_004":{"type":"ItemPartBox","value":{"Element_000":"효과","Element_001":"피해 44.00% 증가"}}}
            """,
            null);
    assertThat(lines.titleLine()).isEqualTo("전설 보석");
    assertThat(lines.itemLevel()).isEqualTo(1640);
    assertThat(lines.tier()).isEqualTo(4);
    assertThat(lines.tradeLines()).containsExactly("장착, 합성, 조율 가능 아이템 레벨 1640", "캐릭터 귀속됨", "거래 불가");
  }
}
