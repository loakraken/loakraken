package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CombatPowerRefineModel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재련 모델 S의 Σ(계산 검증 11-09 방어구 전투력 근본 원인)를 실제 공식 원문(크라켄 점수 표본의 armories 원문)에서 실제 수집 경로와 같은 변환으로 읽는다.
 * 기대값은 원문 툴팁의 방어구 5·팔 보호구·장신구 5 첫 힘·민첩·지능 +N 합(m01은 장신구 한 칸이 비어 10부위). 표본 팔찌에는 힘·민첩·지능 줄이 없다.
 */
class LostArkMainStatSumRawTest {
  private static final JsonMapper MAPPER = new JsonMapper();

  @Test
  void 실제_원문의_장비_주스탯_합을_읽는다() throws Exception {
    var expected =
        Map.of(
            "l01", 705_512L,
            "l02", 831_169L,
            "l03", 831_109L,
            "l04", 710_479L,
            "l05", 740_666L,
            "l06", 829_323L,
            "l07", 826_939L,
            "m01", 356_848L);
    for (var entry : expected.entrySet()) {
      var raw =
          MAPPER
              .readTree(
                  new ClassPathResource("kraken-score/" + entry.getKey() + ".json")
                      .getContentAsString(StandardCharsets.UTF_8))
              .path("raw");
      var sum = CombatPowerRefineModel.mainStatSum(ArmoryRawFixtures.loadout(raw));
      assertThat(sum.reason()).as(entry.getKey()).isNull();
      assertThat(sum.sum()).as(entry.getKey()).isEqualByComparingTo(entry.getValue().toString());
    }
  }
}
