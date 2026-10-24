package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterCardPort;
import com.loakraken.character.application.CharacterCardService;
import com.loakraken.character.domain.CharacterLoadout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 실제 공식 장신구 툴팁 원문(유물 목걸이, 아이템 이름만 있는 장비 정보)으로 연마 효과 줄 기록 값 확인. 장신구 툴팁에는 '아이템 레벨 N' 줄이 없어 파서의
 * itemLevel은 null이고, 기록은 '거래 제한 아이템 레벨 N' 줄에서 레벨을 읽는다.
 */
class LostArkAccessoryPolishRawTest {
  @Test
  void 실제_장신구_원문의_연마_줄은_거래_제한_줄의_아이템_레벨로_기록한다() throws Exception {
    String tooltip;
    try (var in =
        getClass().getResourceAsStream("/fixtures/accessory-tooltip-relic-necklace.json")) {
      tooltip = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var detail = LostArkEquipmentTooltip.parse("엄숙한 결의의 목걸이", tooltip);
    assertThat(detail.itemLevel()).isNull();
    assertThat(detail.tradeLines()).contains("거래 제한 아이템 레벨 1640");

    var lines =
        CharacterCardService.polishLines(
            new CharacterLoadout(
                List.of(new CharacterLoadout.Equipment("목걸이", "엄숙한 결의의 목걸이", null, "유물", detail)),
                null,
                null,
                null,
                null,
                null));
    assertThat(lines)
        .containsExactly(
            new CharacterCardPort.PolishLine("목걸이", 1640, "유물", "추가 피해", "+2.60%", "HIGH"),
            new CharacterCardPort.PolishLine("목걸이", 1640, "유물", "적에게 주는 피해", "+1.20%", "MID"),
            new CharacterCardPort.PolishLine("목걸이", 1640, "유물", "낙인력", "+4.80%", "MID"));
  }
}
