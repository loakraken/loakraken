package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.common.api.EfficiencyRuleVersions;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 랭킹용 실 효율 저장(B3): 관측 때 저장하는 팔찌·젬 실 효율이 캐릭터 응답의 braceletEfficiency.real·arkGridEfficiency.real과 같은
 * 값인지 고정 자료(l01 딜러, l04 서포터)로 본다. 팔찌는 비교 기준 값(BraceletRealServiceTest와 같은 값), 젬은 고정 자료의 기대값이다.
 */
class CharacterCardServiceRealEfficiencyTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final KrakenScoreEngine.Tables TABLES = tables();
  private final CharacterCardService service =
      new CharacterCardService(
          mock(CharacterCardPort.class),
          mock(EquipmentSummaryPort.class),
          mock(CharacterHistoryPolicy.class),
          mock(CharacterMetrics.class),
          mock(CombatPowerSimulationService.class),
          mock(ArkGridEfficiencyService.class),
          new BraceletRealService(TABLES),
          new ArkGridRealService(TABLES));

  private static KrakenScoreEngine.Tables tables() {
    try {
      var doc = read("reference-tables");
      return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JsonNode read(String id) throws IOException {
    return MAPPER.readTree(
        new ClassPathResource("kraken-score/" + id + ".json")
            .getContentAsString(StandardCharsets.UTF_8));
  }

  /** 고정 자료 원문의 직업·아크 패시브 효과로 캐릭터 정보를 만든다. 장비는 관측한 것으로 둔다(없으면 저장값을 바꾸지 않는 경로라서). */
  private static CharacterDetails details(JsonNode raw, boolean equipment) {
    var effects = new ArrayList<CharacterLoadout.PassiveEffect>();
    for (var e : raw.path("ArkPassive").path("Effects"))
      effects.add(
          new CharacterLoadout.PassiveEffect(
              e.path("Name").asString(""),
              e.path("Description").asString("").replaceAll("<[^>]*>", ""),
              null));
    var profile =
        new CharacterProfile(
            "표본",
            "서버",
            raw.path("ArmoryProfile").path("CharacterClassName").asString(""),
            70,
            new BigDecimal("1700"),
            null,
            null,
            null,
            null,
            null,
            List.of());
    var loadout =
        new CharacterLoadout(
            equipment
                ? List.of(new CharacterLoadout.Equipment("무기", "테스트 무기", null, "고대", null))
                : List.of(),
            null,
            null,
            null,
            new CharacterLoadout.ArkPassive("", true, List.of(), effects),
            null);
    return CharacterTestFixture.details(profile, loadout, List.of());
  }

  @ParameterizedTest
  @CsvSource({"l01, 11.3", "l04, 13.47857142857143"})
  void 저장하는_실_효율은_캐릭터_응답의_실_효율과_같은_값이다(String id, String bracelet) throws IOException {
    var doc = read(id);
    var raw = doc.path("raw");
    var input = KrakenScoreInputMapper.from(raw);
    var details = details(raw, true);

    var stored = service.efficiency(details, input, Instant.EPOCH);

    var card =
        new BraceletRealService(TABLES)
            .compute(new CharacterResult(details, Instant.EPOCH, null, input));
    var gem = new ArkGridRealService(TABLES).compute(details, input);
    assertThat(stored.braceletRealPct()).as(id).isEqualTo(new BigDecimal(bracelet));
    assertThat(stored.braceletRealPct()).as(id).isEqualTo(card.percent());
    assertThat(stored.braceletRealRuleVersion()).isEqualTo(EfficiencyRuleVersions.BRACELET_REAL);
    assertThat(stored.arkGridRealPct())
        .as(id)
        .isNotNull()
        .isEqualTo(gem.percent())
        .isEqualByComparingTo(
            BigDecimal.valueOf(
                doc.path("expectedGemEfficiency").path("전체").path("efficiency").asDouble()));
    assertThat(stored.arkGridRealRuleVersion()).isEqualTo(EfficiencyRuleVersions.ARK_GRID_REAL);
  }

  @Test
  void 점수_입력이_없으면_실_효율만_비고_장비가_빈_관측이면_모든_값을_비운다() throws IOException {
    var raw = read("l01").path("raw");
    var pending = service.efficiency(details(raw, true), null, Instant.EPOCH);
    assertThat(pending).isNotNull();
    assertThat(pending.braceletRealPct()).isNull();
    assertThat(pending.braceletRealRuleVersion()).isNull();
    assertThat(pending.arkGridRealPct()).isNull();
    assertThat(pending.arkGridRealRuleVersion()).isNull();

    // 옛 효율이 랭킹에 남지 않게, 장비가 빈 관측은 null이 아니라 모든 값이 빈 레코드다(저장 시 비움).
    assertThat(
            service.efficiency(
                details(raw, false), KrakenScoreInputMapper.from(raw), Instant.EPOCH))
        .isEqualTo(
            new CharacterCardPort.Efficiency(null, null, null, null, null, null, null, null));
  }
}
