package com.loakraken.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccessoryStatRangeTest {
  private static final Map<String, Slot> PARTS =
      Map.of("목걸이", Slot.NECKLACE, "귀걸이", Slot.EARRING, "반지", Slot.RING);

  private static JsonNode sample() throws Exception {
    try (InputStream in =
        AccessoryStatRangeTest.class.getResourceAsStream(
            "/verification/accessory/accessory-stat-range-2026-10-12.json")) {
      return JsonMapper.builder().build().readTree(in);
    }
  }

  @Test
  void 범위표는_검증_표본의_공식_아이템_사전_값과_같다() throws Exception {
    var sample = sample();
    for (var part : PARTS.entrySet())
      for (int stage = 0; stage <= AccessoryStatRange.MAX_STAGE; stage++) {
        var cell = sample.at("/mainStatRange/" + part.getKey() + "/" + stage);
        assertThat(AccessoryStatRange.mainStatMin(part.getValue(), stage))
            .as(part.getKey() + stage)
            .isEqualTo(cell.get(0).asInt());
        assertThat(AccessoryStatRange.mainStatMax(part.getValue(), stage))
            .isEqualTo(cell.get(1).asInt());
      }
    for (var grade : Map.of("유물", Grade.RELIC, "고대", Grade.ANCIENT).entrySet())
      for (var part : PARTS.entrySet())
        assertThat(AccessoryStatRange.enlightenmentMax(grade.getValue(), part.getValue()))
            .isEqualTo(
                sample
                    .at("/enlightenmentRange/" + grade.getKey() + "/" + part.getKey() + "/3")
                    .get(1)
                    .asInt());
  }

  @Test
  void 범위_퍼센트는_기준_캐릭터_5개가_비교_기준값과_같다() throws Exception {
    var rows = sample().at("/samples/rows");
    assertThat(rows.size()).isEqualTo(5);
    for (var row : rows)
      assertThat(
              AccessoryStatRange.mainStatRangePct(
                  PARTS.get(row.path("part").asText()),
                  row.path("polishStage").asInt(),
                  row.path("mainStat").asInt()))
          .as(row.toString())
          .isEqualByComparingTo(row.path("refPct").decimalValue());
  }

  @Test
  void 단계가_범위_밖이거나_힘민지가_없으면_null이다() {
    assertThat(AccessoryStatRange.mainStatRangePct(Slot.RING, 4, 12000)).isNull();
    assertThat(AccessoryStatRange.mainStatRangePct(Slot.RING, 3, null)).isNull();
  }

  @Test
  void 서버_필터_하한은_모든_연마_단계의_필요조건이다() {
    for (var slot : Slot.values())
      for (int pct : new int[] {1, 50, 99, 100}) {
        int bound = AccessoryStatRange.mainStatLowerBound(slot, pct);
        for (int stage = 0; stage <= AccessoryStatRange.MAX_STAGE; stage++) {
          // 그 단계에서 범위 %가 pct가 되는 가장 작은 힘민지도 하한 이상이다.
          int min = AccessoryStatRange.mainStatMin(slot, stage);
          int width = AccessoryStatRange.mainStatMax(slot, stage) - min;
          int smallest = min + (int) Math.ceil(width * pct / 100.0);
          assertThat(smallest).as(slot + " " + pct + " " + stage).isGreaterThanOrEqualTo(bound);
        }
      }
  }
}
