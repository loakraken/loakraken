package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.TestcontainersConfiguration;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.refining.domain.AdvancedRefineRules;
import com.loakraken.specup.domain.SpecUpCost;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 상급 재련 기대 비용 스위치(#1081 끄기, PM 총괄 11-28 03:50): 기본 끔이고 요청마다 읽는다. 꺼져 있으면 #1081 전과 같이 비용 미확정이고 풀이를 하지
 * 않으며(시세가 없어도 PRICE_PENDING이 아니라 미확정), 켜면 기대 비용 길로 간다. 스위치와 상급 재련 규칙 버전이 목표 경로·추천 순서 캐시 키에 들어간다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spec-up.enabled=true")
class SpecUpAdvancedRefineSwitchIntegrationTest {
  private static final String SWITCH = "advanced-refine-switch-test";
  @Autowired SpecUpService specUp;
  @Autowired AdvancedRefineRules advancedRules;
  @Autowired ConfigurableEnvironment environment;

  @AfterEach
  void 정리() {
    environment.getPropertySources().remove(SWITCH);
  }

  private static CharacterLoadout.Equipment uphwaArmor() {
    return new CharacterLoadout.Equipment(
        "상의",
        "+20 운명의 업화 상의",
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            20, 100, 1730, 4, null, List.of(), List.of(), List.of(), List.of()));
  }

  @Test
  void 꺼져_있으면_상급_재련_비용은_미확정이고_켜면_기대_비용_길로_간다() {
    var item = uphwaArmor();

    var off = specUp.advancedRefineCost(item, 10, 11, Optional.empty(), new Instant[1]);
    var offKey = specUp.costRuleVersions();

    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(SWITCH, Map.of("spec-up.advanced-refine-cost.enabled", "true")));
    var on = specUp.advancedRefineCost(item, 10, 11, Optional.empty(), new Instant[1]);
    var onKey = specUp.costRuleVersions();

    assertThat(off).isEqualTo(SpecUpCost.REFINE_COST_UNCONFIRMED);
    // 켜면 기대 비용 길: 시세 캐시가 없어 PRICE_PENDING(꺼졌을 때와 다른 값).
    assertThat(on).isEqualTo(SpecUpCost.REFINE_PRICE_PENDING);
    assertThat(offKey.split("\\+")).contains("adv-off");
    assertThat(onKey.split("\\+")).contains(advancedRules.ruleVersion()).doesNotContain("adv-off");
  }
}
