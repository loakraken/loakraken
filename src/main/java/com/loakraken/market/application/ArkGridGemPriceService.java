package com.loakraken.market.application;

import com.loakraken.market.domain.ArkGridGemCatalog;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 아크 그리드 젬 시세(M49 G5, PM 총괄 11-22). 품목은 {@link ArkGridGemCatalog}(공식 230000 분류 전수 조회), 시세는 캐시에 이미
 * 있는 아크 그리드 탭 전체 스캔에서만 읽는다 — 이 서비스는 스캔하지 않고 공식 호출도 없다. 스캔은 보드 방문과 젬 교체 순위 예열(10분마다, 젬 순위 스위치가 켜져 있을
 * 때만)이 받고, 저장할 때 젬 순위용 스냅숏(15분)도 함께 갱신된다(PM 총괄 2026-11-22).
 *
 * <p>ID와 공식 이름이 모두 맞는 매물만 쓴다(이름이 바뀌면 시세 없음). 시세가 없거나 0이면 빈 값이다 — 추정하지 않는다(쓰는 쪽은 '시세 없음'). 젬 가공
 * 초기화권은 이 분류에 없어 사용자 입력 가격이다.
 */
@Service
public class ArkGridGemPriceService {
  private final MarketCache cache;
  private final ArkGridGemCatalog catalog = ArkGridGemCatalog.load();

  public ArkGridGemPriceService(MarketCache cache) {
    this.cache = cache;
  }

  /**
   * 젬 순위 스냅숏(15분)에서 읽고, 없으면 보드 캐시(3분)의 아크 그리드 탭 스캔에서 읽는다 — 스캔하지 않고 공식 호출도 없다(M49 2단계 젬 순위: 사용자 요청
   * 경로). 둘 다 없거나 읽지 못하면 빈 목록(쓰는 쪽은 시세 없음).
   */
  public List<Price> cachedPrices() {
    if (cache == null) return List.of();
    Optional<MarketBoardScan> scan;
    try {
      scan = cache.findArkGridGemSnapshot();
      if (scan.isEmpty())
        scan = cache.findBoardScan(MarketTab.ARK_GRID, MarketTab.ARK_GRID.defaultTier());
    } catch (RuntimeException exception) {
      return List.of();
    }
    if (scan.isEmpty()) return List.of();
    var result = new ArrayList<Price>();
    for (var gem : catalog.gems()) priced(gem, scan.get()).ifPresent(result::add);
    return List.copyOf(result);
  }

  /** 젬 하나의 1개 즉시 구매 최저가(묶음이면 1개 값, 올림)와 스캔 관측 시각. */
  public record Price(long gemId, String type, String grade, long unitPrice, Instant observedAt) {}

  private static Optional<Price> priced(ArkGridGemCatalog.Gem gem, MarketBoardScan scan) {
    return scan.items().stream()
        .filter(item -> item.id() == gem.id() && gem.name().equals(item.name()))
        .filter(item -> item.currentMinPrice() != null && item.currentMinPrice().signum() > 0)
        .findFirst()
        .map(item -> new Price(gem.id(), gem.type(), gem.grade(), unit(item), scan.observedAt()));
  }

  private static long unit(MarketPrice item) {
    return item.currentMinPrice()
        .divide(BigDecimal.valueOf(item.bundleCount()), 0, RoundingMode.CEILING)
        .longValue();
  }
}
