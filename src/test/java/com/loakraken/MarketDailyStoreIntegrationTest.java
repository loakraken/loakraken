package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketItemHistory.DailyStat;
import com.loakraken.market.domain.MarketItemHistory.Variant;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 거래소 일별 시세 표(market_price_daily): 완료일만, 변형마다, 같은 날은 더 늦은 관측만 덮고, 지우지 않는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MarketDailyStoreIntegrationTest {
  private static final long ITEM = 990101L;
  private static final LocalDate TODAY = LocalDate.parse("2026-11-12");
  private static final Instant FIRST = Instant.parse("2026-11-11T17:00:00Z");
  @Autowired MarketDailyStore store;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM market_price_daily WHERE item_id BETWEEN 990100 AND 990199").update();
  }

  @Test
  void 완료일을_변형마다_저장하고_같은_날은_더_늦은_관측만_덮는다() {
    var history =
        new MarketItemHistory(
            ITEM,
            List.of(
                new Variant(
                    "운명의 파괴석",
                    null,
                    100,
                    List.of(
                        day("2026-11-12", "9.5", 50), // 오늘(부분 집계)은 넣지 않는다
                        day("2026-11-11", "10.2", 1200),
                        day("2026-11-10", "11", 900))),
                new Variant("운명의 파괴석", 5, 100, List.of(day("2026-11-11", "10.4", 30)))));

    assertThat(store.upsert(history, TODAY, FIRST)).isEqualTo(3);
    assertThat(rows())
        .containsExactly(
            "-1/100/2026-11-10/11.0000/900",
            "-1/100/2026-11-11/10.2000/1200",
            "5/100/2026-11-11/10.4000/30");

    // 같은 값을 다시 받으면(예열마다) 쓰지 않는다.
    assertThat(store.upsert(history, TODAY, FIRST.plusSeconds(30))).isZero();
    // 더 이른 관측은 덮지 않고, 더 늦은 관측은 덮는다.
    var changed =
        new MarketItemHistory(
            ITEM, List.of(new Variant("운명의 파괴석", null, 100, List.of(day("2026-11-11", "99", 1)))));
    store.upsert(changed, TODAY, FIRST.minusSeconds(60));
    assertThat(rows()).contains("-1/100/2026-11-11/10.2000/1200");
    store.upsert(changed, TODAY, FIRST.plusSeconds(60));
    assertThat(rows()).contains("-1/100/2026-11-11/99.0000/1");
    // 이전 날짜는 그대로 남는다(정리 없음).
    assertThat(rows()).contains("-1/100/2026-11-10/11.0000/900");
  }

  /** 줄 하나가 틀려도(이름 길이 초과·숫자 칸 넘침·같은 변형 칸) 그 줄만 건너뛰고 나머지는 저장한다(지표 남김). */
  @Test
  void 잘못된_줄은_그_줄만_건너뛰고_나머지는_저장한다() {
    var history =
        new MarketItemHistory(
            ITEM,
            List.of(
                new Variant("정상", null, 1, List.of(day("2026-11-11", "10", 5))),
                new Variant("가".repeat(129), 3, 1, List.of(day("2026-11-11", "10", 5))),
                new Variant("같은 칸", null, 1, List.of(day("2026-11-10", "12", 6))),
                new Variant(
                    "넘침",
                    7,
                    1,
                    List.of(day("2026-11-11", "1000000000000", 1), day("2026-11-10", "9", 2)))));

    assertThat(store.upsert(history, TODAY, FIRST)).isEqualTo(2);
    assertThat(rows()).containsExactly("-1/1/2026-11-11/10.0000/5", "7/1/2026-11-10/9.0000/2");
  }

  private List<String> rows() {
    return jdbc.sql(
            """
        SELECT trade_remain_key, bundle_key, kst_date, avg_price, trade_count FROM market_price_daily
        WHERE item_id = :id ORDER BY trade_remain_key, bundle_key, kst_date
        """)
        .param("id", ITEM)
        .query(
            (row, index) ->
                row.getInt("trade_remain_key")
                    + "/"
                    + row.getInt("bundle_key")
                    + "/"
                    + row.getObject("kst_date", LocalDate.class)
                    + "/"
                    + row.getBigDecimal("avg_price").toPlainString()
                    + "/"
                    + row.getLong("trade_count"))
        .list();
  }

  private static DailyStat day(String date, String price, long trades) {
    return new DailyStat(LocalDate.parse(date), new BigDecimal(price), trades);
  }
}
