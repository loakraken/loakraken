package com.loakraken.stats.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.stats.domain.StatsRules.Tier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 다음 티어 경계(L2): 기존 rank·topPercent·Tier.of 정의를 거꾸로 푼 값이 맞는지 무작위 모집단으로 확인한다. */
class StatsRulesTierBoundaryTest {
  private static final BigDecimal CENT = new BigDecimal("0.01");

  @Test
  void 다음_티어와_경계_순위() {
    assertThat(Tier.TOP1.next()).isNull();
    assertThat(Tier.TOP5.next()).isEqualTo(Tier.TOP1);
    assertThat(Tier.REST.next()).isEqualTo(Tier.TOP50);
    assertThat(StatsRules.tierRank(Tier.TOP10, 40)).isEqualTo(4);
    assertThat(StatsRules.tierRank(Tier.TOP1, 40)).isZero();
    assertThat(StatsRules.tierRank(Tier.TOP1, 100)).isEqualTo(1);
    assertThat(StatsRules.tierRank(Tier.TOP25, 31)).isEqualTo(7);
  }

  @Test
  void 나를_뺀_K번째는_전체_K번째와_같고_그_값에_닿으면_다음_티어다() {
    var random = new Random(20261013);
    int checked = 0;
    for (int round = 0; round < 300; round++) {
      int total = 30 + random.nextInt(400);
      var values = new ArrayList<BigDecimal>();
      for (int i = 0; i < total; i++)
        // 같은 값(동률)이 자주 생기게 범위를 좁힌다.
        values.add(BigDecimal.valueOf(1000 + random.nextInt(total / 2 + 1), 0).setScale(2));
      for (int me = 0; me < total; me++) {
        var mine = values.get(me);
        var tier = tierOf(values, mine);
        var next = tier.next();
        if (next == null) continue;
        long k = StatsRules.tierRank(next, total);
        if (k == 0) continue;
        var all = sortedDesc(values);
        var others = new ArrayList<>(values);
        others.remove(me);
        var withoutMe = sortedDesc(others);
        // 다음 티어에 못 닿은 캐릭터: 나를 뺀 K번째 = 전체 K번째(캐릭터마다 구하지 않아도 되는 근거).
        assertThat(withoutMe.get((int) k - 1)).isEqualByComparingTo(all.get((int) k - 1));
        var boundary = all.get((int) k - 1);
        assertThat(boundary).isGreaterThan(mine);
        // 그 값에 닿으면 다음 티어(또는 더 위), 0.01 모자라면 아니다.
        var reached = new ArrayList<>(values);
        reached.set(me, boundary);
        assertThat(tierOf(reached, boundary).ordinal()).isLessThanOrEqualTo(next.ordinal());
        var short1 = new ArrayList<>(values);
        short1.set(me, boundary.subtract(CENT));
        assertThat(tierOf(short1, boundary.subtract(CENT)).ordinal()).isGreaterThan(next.ordinal());
        checked++;
      }
    }
    assertThat(checked).isGreaterThan(10_000);
  }

  /** 서비스와 같은 식: rank = 큰 수 + 1, topPercent = ceil₂(rank×100/total), Tier.of. */
  private static Tier tierOf(List<BigDecimal> values, BigDecimal value) {
    long greater = values.stream().filter(v -> v.compareTo(value) > 0).count();
    return Tier.of(StatsRules.topPercent(greater + 1, values.size()));
  }

  private static List<BigDecimal> sortedDesc(List<BigDecimal> values) {
    return values.stream().sorted(Comparator.reverseOrder()).toList();
  }
}
