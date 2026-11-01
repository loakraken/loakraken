package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LostArkAvatarTooltipTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 저장된 실제 공식 raw(2026-10-18)의 아바타 툴팁 원문: 전설 상의, 영웅 얼굴2, 영웅 하의, 이동 효과. */
  @Test
  void 실제_툴팁의_성향_줄을_종류와_점수로_준다() throws IOException {
    var tooltips = realTooltips();
    assertThat(LostArkAvatarTooltip.tendencies(tooltips.get(0)))
        .containsExactly(
            tendency("지성", 5), tendency("담력", 10), tendency("매력", 5), tendency("친절", 5));
    assertThat(LostArkAvatarTooltip.tendencies(tooltips.get(1))).containsExactly(tendency("지성", 5));
    assertThat(LostArkAvatarTooltip.tendencies(tooltips.get(2)))
        .containsExactly(tendency("담력", 5), tendency("매력", 15));
    // 이동 효과에는 성향 줄이 없다.
    assertThat(LostArkAvatarTooltip.tendencies(tooltips.get(3))).isEmpty();
  }

  /** 수집 경로(공식 ArmoryAvatars → 도메인)도 성향을 싣는다. */
  @Test
  void 아바타_도메인에_성향이_실린다() throws IOException {
    var avatar =
        new LostArkArmoryResponse.Avatar(
                "하의 아바타", "하의", null, "영웅", false, false, realTooltips().get(2))
            .toDomain();
    assertThat(avatar.tendencies()).containsExactly(tendency("담력", 5), tendency("매력", 15));
  }

  /** 요소 번호는 아바타마다 달라(Element_004~007) 제목으로 찾고, 제목이 다른 SymbolString은 읽지 않는다. */
  @Test
  void 요소_번호가_아니라_성향_제목으로_찾는다() {
    var parsed =
        LostArkAvatarTooltip.tendencies(
            """
            {"Element_004":{"type":"SymbolString","value":{"contentStr":"지성 : 99<BR>","titleStr":"다른 제목"}},
             "Element_007":{"type":"SymbolString","value":{"contentStr":"&tdc_kind 친절 : 10<BR><BR>","titleStr":"<FONT COLOR='#A9D0F5'>성향</FONT>"}}}
            """);
    assertThat(parsed).containsExactly(tendency("친절", 10));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "not json", "[]", "{\"Element_000\":null}"})
  void 툴팁이_없거나_깨지면_빈_목록이다(String tooltip) {
    assertThat(LostArkAvatarTooltip.tendencies(tooltip)).isEmpty();
  }

  private static CharacterLoadout.AvatarTendency tendency(String type, int point) {
    return new CharacterLoadout.AvatarTendency(type, point);
  }

  private static List<String> realTooltips() throws IOException {
    try (InputStream input =
        LostArkAvatarTooltipTest.class.getResourceAsStream("/fixtures/avatar-tooltips-real.json")) {
      var tooltips = new ArrayList<String>();
      for (var avatar : MAPPER.readTree(input).values()) tooltips.add(avatar.path("tooltip").stringValue());
      return tooltips;
    }
  }
}
