package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 보석 반영 쿨타임(#377, 스킬 피해 보고서 1단계, PM 총괄 10-11 21:57 ④(b)). 공식 Tooltip 제목 줄의 "재사용 대기시간 N초"(선택한 트라이포드가
 * 반영된 값) × (1 − 그 스킬 보석 효과 "재사용 대기시간 x% 감소" 합/100). 각인·아크 패시브·신속·아이덴티티 감소는 넣지 않는다(두 출처 대조 뒤 단계적으로).
 * 공식 원문만 쓰므로 계산이 확정된다(계산 검증 표본 #353).
 */
public record SkillCooldown(
    BigDecimal cooldownSec, BigDecimal gemCooldownReductionPct, BigDecimal cooldownWithGemsSec) {
  public static final String BASIS = "공식 재사용 대기시간과 장착 보석의 재사용 대기시간 감소만 반영했습니다.";

  private static final Pattern OFFICIAL =
      Pattern.compile("^재사용 대기시간\\s*(?:(\\d+)분)?\\s*(?:(\\d+(?:\\.\\d+)?)초)?$");
  private static final Pattern GEM = Pattern.compile("재사용 대기시간\\s*([0-9.]+)%\\s*감소$");

  /** 제목 줄에 재사용 대기시간이 없으면(이전 캐시 등) null이다. */
  public static SkillCooldown of(CharacterLoadout.Skill skill) {
    if (skill == null) return null;
    var official = official(skill.titleLines());
    if (official == null) return null;
    var pct = BigDecimal.ZERO;
    for (var effect : skill.gemEffects()) {
      if (effect == null || effect.kind() != CharacterLoadout.GemEffectKind.COOLDOWN) continue;
      var matcher = GEM.matcher(effect.text());
      if (matcher.find()) pct = pct.add(new BigDecimal(matcher.group(1)));
    }
    return new SkillCooldown(scale(official), scale(pct), withGems(official, pct));
  }

  /** cooldown × (1 − pct/100), 소수 둘째 자리 반올림. */
  static BigDecimal withGems(BigDecimal cooldown, BigDecimal pct) {
    return scale(cooldown.multiply(BigDecimal.ONE.subtract(pct.movePointLeft(2))));
  }

  private static BigDecimal official(List<String> titleLines) {
    for (var line : titleLines) {
      var matcher = OFFICIAL.matcher(line.strip());
      if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) continue;
      var seconds = matcher.group(2) == null ? BigDecimal.ZERO : new BigDecimal(matcher.group(2));
      if (matcher.group(1) != null)
        seconds = seconds.add(BigDecimal.valueOf(Long.parseLong(matcher.group(1)) * 60));
      return seconds;
    }
    return null;
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
