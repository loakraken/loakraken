package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CombatPowerPolishValues;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연마 교체 줄의 전투력 목표 줄(비교 기준 B 스펙업 후보 규칙, 계산 검증 11-15): 주옵션은 목표 등급, 지금 고정값 공격력·무기 공격력 줄은 빠짐(null = 전투력
 * 몫 0), 두 주옵션이 모두 상이면 고정값 줄은 남아 후보 값이 된다, 주옵션도 고정값도 아닌 줄은 그대로. 합성 장신구(캐릭터 원문 아님).
 */
class SpecUpPolishSwapLinesTest {
  private static final List<String> EARRING = List.of("무기 공격력", "공격력");

  private static CombatPowerPolishValues values() throws Exception {
    try (var stream = new ClassPathResource("combat-power/polish-values.json").getInputStream()) {
      return JsonMapper.builder().build().readValue(stream, CombatPowerPolishValues.class);
    }
  }

  @Test
  void 주옵션이_상_둘이_아니면_고정값_줄은_빠지고_주옵션은_제자리에서_등급만_바뀐다() throws Exception {
    var swap =
        SpecUpScoreCandidates.swapLines(
            values(),
            "귀걸이",
            EARRING,
            List.of("무기 공격력 +1.80%", "공격력 +80", "공격력 +0.40%"),
            new int[] {0, 2},
            List.of(1),
            new int[] {1, 1},
            null);
    assertThat(swap).containsExactly("무기 공격력 +1.80%", null, "공격력 +0.95%");
  }

  @Test
  void 주옵션도_고정값도_아닌_줄은_그대로이고_새_주옵션은_빠지는_고정값_자리에_놓인다() throws Exception {
    var swap =
        SpecUpScoreCandidates.swapLines(
            values(),
            "귀걸이",
            EARRING,
            List.of("무기 공격력 +1.80%", "공격력 +80", "파티원 회복 효과 +0.95%"),
            new int[] {0, -1},
            List.of(1),
            new int[] {1, 0},
            null);
    assertThat(swap).containsExactly("무기 공격력 +1.80%", "공격력 +0.40%", "파티원 회복 효과 +0.95%");
    // 고정값 줄이 없으면 주옵션 아닌 줄 자리에.
    var noFlat =
        SpecUpScoreCandidates.swapLines(
            values(),
            "귀걸이",
            EARRING,
            List.of("무기 공격력 +1.80%", "파티원 회복 효과 +0.95%", "최대 생명력 +1300"),
            new int[] {0, -1},
            List.of(),
            new int[] {1, 2},
            null);
    assertThat(noFlat).containsExactly("무기 공격력 +1.80%", "공격력 +1.55%", "최대 생명력 +1300");
  }

  @Test
  void 주옵션이_모두_상이면_고정값_줄은_남아_셋째_줄_후보_값이_된다() throws Exception {
    var keep =
        SpecUpScoreCandidates.swapLines(
            values(),
            "귀걸이",
            EARRING,
            List.of("무기 공격력 +3.00%", "공격력 +80", "공격력 +1.55%"),
            new int[] {0, 2},
            List.of(1),
            new int[] {2, 2},
            "공격력 +390");
    assertThat(keep).containsExactly("무기 공격력 +3.00%", "공격력 +390", "공격력 +1.55%");
    // 고정값 줄이 없으면(셋째 줄이 다른 옵션) 그 줄 자리에 상 고정값.
    var none =
        SpecUpScoreCandidates.swapLines(
            values(),
            "귀걸이",
            EARRING,
            List.of("최대 생명력 +1300", "무기 공격력 +3.00%", "공격력 +1.55%"),
            new int[] {1, 2},
            List.of(),
            new int[] {2, 2},
            "무기 공격력 +960");
    assertThat(none).containsExactly("무기 공격력 +960", "무기 공격력 +3.00%", "공격력 +1.55%");
  }
}
