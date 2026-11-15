package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerRefineModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 덧입기 표시 줄(공식 툴팁 "아래 능력들이 적용되지 않는 상태입니다.(덧입기 슬롯에 착용 중)", 저장된 원문 2026-10-18)을 원문 정리 전 단계에서 읽는다.
 * IsInner=false는 덧입기만이 아니라 아래에 아무것도 없는 보통 장착 아바타에도 붙으므로, 효과에서 빼는 기준은 이 줄이다. 공식 호출 0.
 */
class AvatarOverlayMarkerRawTest {
  private static final String FIXTURE =
      "verification/combat-power/avatar-overlay-marker-2026-10-18.json";
  private static final JsonMapper MAPPER = new JsonMapper();

  @Test
  void 실제_원문에서_표시_줄이_있는_덧입기만_효과에서_빼고_세_캐릭터_주스탯은_예전_규칙과_같다() throws Exception {
    var coefficients = coefficients();
    var sums = new LinkedHashMap<String, String>();
    var marked = new ArrayList<String>();
    for (var character : fixture().path("characters")) {
      var label = character.path("label").asText();
      var avatars = new ArrayList<CharacterLoadout.Avatar>();
      for (var raw : character.path("avatars")) {
        var avatar = domain(raw, raw.path("Tooltip").asText());
        if (Boolean.TRUE.equals(avatar.effectsDisabled())) marked.add(label + " " + avatar.type());
        else assertThat(avatar.effectsDisabled()).as(label + " " + avatar.type()).isFalse();
        avatars.add(avatar);
      }
      sums.put(
          label,
          CombatPowerRefineModel.avatarStat(coefficients, avatars)
              .sum()
              .multiply(BigDecimal.valueOf(100))
              .stripTrailingZeros()
              .toPlainString());
    }
    // 표시 줄은 두 겹 캐릭터의 영웅 덧입기 네 칸에만 있다(혼자 입은 IsInner=false 영웅에는 없음).
    assertThat(marked)
        .containsExactly(
            "inner-legend-b 무기 아바타",
            "inner-legend-b 머리 아바타",
            "inner-legend-b 상의 아바타",
            "inner-legend-b 하의 아바타");
    // 펫 1%를 더하면 mS 1.07 · 1.09 · 1.05(예전 '칸마다 큰 값' 규칙과 같음)
    assertThat(sums)
        .containsExactly(
            java.util.Map.entry("lone-hero-a", "6"),
            java.util.Map.entry("inner-legend-b", "8"),
            java.util.Map.entry("lone-hero-c", "4"));
  }

  @Test
  void 표시_줄은_공식_문구_그대로일_때만_맞는다() throws Exception {
    JsonNode overlay = null;
    for (var character : fixture().path("characters"))
      for (var raw : character.path("avatars"))
        if (raw.path("Tooltip").asText().contains("덧입기 슬롯에 착용 중")) overlay = raw;
    assertThat(overlay).isNotNull();
    var tooltip = overlay.path("Tooltip").asText();
    assertThat(domain(overlay, tooltip).effectsDisabled()).isTrue();
    // 문구가 바뀌면(뒤 괄호가 없음) 덧입기로 보지 않고 센다(덜 세는 쪽으로 틀리지 않게)
    var changed = tooltip.replace("(덧입기 슬롯에 착용 중)", "");
    assertThat(domain(overlay, changed).effectsDisabled()).isFalse();
    // 원문이 없으면 모름(null) → 예전 규칙
    assertThat(LostArkAvatarTooltip.effectsDisabled(null)).isNull();
  }

  private static CharacterLoadout.Avatar domain(JsonNode raw, String tooltip) {
    return new LostArkArmoryResponse.Avatar(
            raw.path("Type").asText(),
            raw.path("Name").asText(),
            null,
            raw.path("Grade").asText(),
            raw.path("IsSet").isNull() ? null : raw.path("IsSet").asBoolean(),
            raw.path("IsInner").asBoolean(),
            tooltip)
        .toDomain();
  }

  private static JsonNode fixture() throws Exception {
    try (var input = new ClassPathResource(FIXTURE).getInputStream()) {
      return MAPPER.readTree(input);
    }
  }

  private static CombatPowerRefineModel.Coefficients coefficients() throws Exception {
    try (var input =
        new ClassPathResource("combat-power/refine-coefficients.json").getInputStream()) {
      return MAPPER.readValue(input, CombatPowerRefineModel.Coefficients.class);
    }
  }
}
