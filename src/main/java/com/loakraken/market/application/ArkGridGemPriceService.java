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
 * 아크 그리드 젬 시세(M49 G5, PM 총괄 11-22). 품목은 {@link ArkGridGemCatalog}(공식 230000 분류 전수 조회), 시세는 시세 보드 아크
 * 그리드 탭 전체 스캔({@link MarketBoardService#scanForVolume})에서 읽는다 — 보드 상승·하락·거래량 정렬과 같은 캐시·합치기·한 번에
 * 하나이고, 캐시에 없을 때만 공식 검색을 부른다(이 탭은 2쪽). 새 공식 호출 경로·배경 작업은 없다.
 *
 * <p>ID와 공식 이름이 모두 맞는 매물만 쓴다(이름이 바뀌면 시세 없음). 시세가 없거나 0이거나 스캔이 실패하면 빈 값이다 — 추정하지 않는다(쓰는 쪽은 '시세 없음').
 * 젬 가공 초기화권은 이 분류에 없어 사용자 입력 가격이다.
 */
@Service
public class ArkGridGemPriceService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ArkGridGemPriceService.class);
  private final MarketBoardService boards;
  private final MarketCache cache;
  private final ArkGridGemCatalog catalog = ArkGridGemCatalog.load();

  public ArkGridGemPriceService(MarketBoardService boards) {
    this(boards, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ArkGridGemPriceService(MarketBoardService boards, MarketCache cache) {
    this.boards = boards;
    this.cache = cache;
  }

  /**
   * 캐시에 이미 있는 아크 그리드 탭 스캔에서만 읽는다 — 스캔하지 않고 공식 호출도 없다(M49 2단계 젬 순위: 사용자 요청 경로, PM 총괄 11-22 · #1087
   * 리뷰 P3). 캐시가 없거나 읽지 못하면 빈 목록(쓰는 쪽은 시세 없음).
   */
  public List<Price> cachedPrices() {
    if (cache == null) return List.of();
    Optional<MarketBoardScan> scan;
    try {
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

  /** 종류·등급 젬의 시세. 모르는 종류·등급, 시세 없음, 스캔 실패는 빈 값. */
  public Optional<Price> price(String type, String grade) {
    var gem = catalog.find(type, grade);
    if (gem.isEmpty()) return Optional.empty();
    return scan().flatMap(scan -> priced(gem.get(), scan));
  }

  /** 시세가 있는 젬 전부(스캔 한 번). 스캔 실패는 빈 목록. 여러 젬을 한 번에 값 매기는 쪽은 price를 젬마다 부르지 말고 이것을 한 번 부른다. */
  public List<Price> prices() {
    var scan = scan();
    if (scan.isEmpty()) return List.of();
    var result = new ArrayList<Price>();
    for (var gem : catalog.gems()) priced(gem, scan.get()).ifPresent(result::add);
    return List.copyOf(result);
  }

  private Optional<MarketBoardScan> scan() {
    try {
      return Optional.ofNullable(boards.scanForVolume(MarketTab.ARK_GRID));
    } catch (RuntimeException exception) {
      // 시세 없음으로 두되(추정하지 않음) 스캔 실패는 보이게 한다. 예외 유형만 남긴다.
      log.warn(
          "event=ark_grid_gem_scan_failed exceptionType={}", exception.getClass().getSimpleName());
      return Optional.empty();
    }
  }

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
