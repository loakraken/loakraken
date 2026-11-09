package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CritSpeedCalculation;
import com.loakraken.character.domain.CritSpeedCalculation.Assumptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 치명·속도 끝값 고정(계산 검증 11-09 21:13). 표본은 reference character, 11-08 snapshot의 공식 원문을 계산에 쓰는 부분만 남기고
 * 이름·서버·길드·칭호를 지운 사본(verification/crit-speed/reference-weather-artist-2026-11-08.json)이고, 실제 수집 경로와
 * 같은 변환(원문 → 프로필·장비)을 거친다. 요청은 웹 기본 설정과 같다: 백어택 켬, 공격·이동 속도 가정 각 14(갈망 9 + 만찬 5), 직업 상태 켬. 비교 기준 값:
 * 주력 스킬 치명타 적중률 92.23, 치명타 피해 315.80.
 */
class CritSpeedReferenceSnapshotTest {
  private static final JsonMapper MAPPER = new JsonMapper();

  @Test
  void 웹_기본_요청으로_표본_캐릭터의_치명타_적중률과_치명타_피해가_비교_기준과_같다() throws Exception {
    var raw =
        MAPPER.readTree(
            new ClassPathResource(
                    "verification/crit-speed/reference-weather-artist-2026-11-08.json")
                .getContentAsString(StandardCharsets.UTF_8));
    var armory = MAPPER.treeToValue(raw, LostArkArmoryResponse.class);
    var profile = LostArkProfileMapper.from(armory.profile());
    var loadout = armory.toLoadout();
    var web =
        new Assumptions(
            true,
            false,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("14"),
            new BigDecimal("14"),
            true,
            Map.of());

    var result = CritSpeedCalculation.calculate(profile, loadout, web);

    // 치명 40.73 + 아드레날린 20 + 달인 7 + 반지 0.95·1.55 + 기민함 12(이동 40% × 30%) + 급소 노출 시너지(자신) 10
    assertThat(result.crit().mainSkillRate()).isEqualByComparingTo("92.23");
    // 기본 200 + 예리한 둔기 61.4 + 반지 4·2.4 + 기민함 48(공격 40% × 120%)
    assertThat(result.crit().damage().total()).isEqualByComparingTo("315.8");
    assertThat(result.sources())
        .filteredOn(source -> source.name().startsWith("기민함("))
        .extracting(
            source -> source.name() + "=" + source.value().stripTrailingZeros().toPlainString())
        .containsExactly("기민함(이동 속도 40%)=12", "기민함(공격 속도 40%)=48");
  }
}
