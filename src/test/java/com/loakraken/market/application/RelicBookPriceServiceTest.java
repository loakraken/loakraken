package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 유물 각인서 시세(L30). 이름 형식은 공식 거래소 "유물 {각인} 각인서"(2026-10-12 조회, 띄어쓰기가 각인 이름과 다를 수 있음). */
class RelicBookPriceServiceTest {
  private final MarketCache cache = mock(MarketCache.class);
  private final RelicBookPriceService service =
      new RelicBookPriceService(mock(MarketQueryService.class), cache);
  private final Instant at = Instant.parse("2026-10-12T04:00:00Z");

  @Test
  void 공백을_빼고_이름을_맞추고_묶음이면_1권_값을_올림한다() {
    when(cache.findRelicBooks())
        .thenReturn(
            Optional.of(
                new RelicBookScan(
                    at,
                    3,
                    List.of(
                        price(1, "유물 실드관통 각인서", 1, "2"),
                        price(2, "유물 원한 각인서", 3, "100"),
                        price(3, "유물 저주받은 인형 각인서", 1, null)))));
    assertThat(service.price("실드 관통")).contains(new RelicBookPriceService.Price(2, at));
    assertThat(service.price("원한")).contains(new RelicBookPriceService.Price(34, at));
    assertThat(service.price("저주받은 인형")).isEmpty();
    assertThat(service.price("없는 각인")).isEmpty();
  }

  @Test
  void 캐시가_없거나_읽지_못하면_빈_값이다() {
    when(cache.findRelicBooks()).thenReturn(Optional.empty());
    assertThat(service.price("원한")).isEmpty();
    when(cache.findRelicBooks()).thenThrow(new IllegalStateException("down"));
    assertThat(service.price("원한")).isEmpty();
    assertThat(service.price(null)).isEmpty();
  }

  @Test
  void 아이콘은_같은_이름_유물_각인서의_공식_아이콘이고_시세가_없어도_쓴다() {
    when(cache.findRelicBooks())
        .thenReturn(
            Optional.of(
                new RelicBookScan(
                    at,
                    3,
                    List.of(
                        new MarketPrice(
                            1,
                            "유물 원한 각인서",
                            "유물",
                            "https://cdn.example/a.png",
                            1,
                            null,
                            null,
                            null,
                            null),
                        new MarketPrice(2, "유물 실드관통 각인서", "유물", " ", 1, null, null, null, null),
                        new MarketPrice(
                            3,
                            "전설 아드레날린 각인서",
                            "전설",
                            "https://cdn.example/b.png",
                            1,
                            null,
                            null,
                            null,
                            null)))));
    assertThat(service.icon("원한")).contains("https://cdn.example/a.png");
    assertThat(service.icon("실드 관통")).isEmpty();
    assertThat(service.icon("아드레날린")).isEmpty();
    assertThat(service.icon(null)).isEmpty();
    when(cache.findRelicBooks()).thenThrow(new IllegalStateException("down"));
    assertThat(service.icon("원한")).isEmpty();
  }

  private static MarketPrice price(long id, String name, int bundle, String min) {
    return new MarketPrice(
        id, name, "유물", null, bundle, null, null, null, min == null ? null : new BigDecimal(min));
  }
}
