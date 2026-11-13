package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.domain.MarketTab;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 시세 거래량 채우기(거래량 정렬). 탭마다 기본 티어 전체 스캔의 품목 상세 이력을 하루 한 번 받아 완료일 거래량을 거래량 지도(Valkey, TTL 36시간)에 둔다.
 *
 * @param enabled 기능 플래그(기본 꺼짐)
 * @param interval 확인 주기. 한 번에 분당 상한만큼 부르고 다음 주기에 이어 받는다
 * @param initialDelay 시작 지연
 * @param maxCallsPerMinute 이 작업의 분당 공식 호출 상한(기본 15). 탭 스캔 한 번을 10번으로 미리 세므로 10 이상이어야 한다
 * @param maxCallsPerDay 이 작업의 하루(KST) 공식 호출 상한(기본 800). 인스턴스가 바뀌어도 Valkey 카운터로 이어 센다
 * @param tabs 채울 탭(기본 보드 탭 전부, 이 순서로)
 */
@ConfigurationProperties("market.volume-warmup")
public record MarketVolumeWarmupProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("60s") Duration interval,
    @DefaultValue("2m") Duration initialDelay,
    @DefaultValue("15") int maxCallsPerMinute,
    @DefaultValue("800") int maxCallsPerDay,
    @DefaultValue({
          "REFINING",
          "REFINING_EXTRA",
          "ARK_GRID",
          "WEAPON_EVOLUTION",
          "ENGRAVING_BOOK",
          "LIFE",
          "BATTLE_ITEM",
          "FOOD"
        })
        List<MarketTab> tabs,
    List<MarketTab> rotatingTabs) {
  public MarketVolumeWarmupProperties {
    if (interval == null || interval.isZero() || interval.isNegative())
      throw new IllegalArgumentException("거래량 채우기 주기는 양수여야 합니다.");
    if (initialDelay == null || initialDelay.isNegative())
      throw new IllegalArgumentException("거래량 채우기 시작 지연은 0 이상이어야 합니다.");
    if (maxCallsPerMinute < MarketBoardService.MAX_SCAN_PAGES)
      throw new IllegalArgumentException(
          "거래량 채우기 분당 상한은 탭 스캔 한 번(" + MarketBoardService.MAX_SCAN_PAGES + "번) 이상이어야 합니다.");
    if (maxCallsPerDay < maxCallsPerMinute)
      throw new IllegalArgumentException("거래량 채우기 하루 상한은 분당 상한 이상이어야 합니다.");
    tabs = tabs == null ? List.of() : tabs.stream().distinct().toList();
    rotatingTabs = rotatingTabs == null ? List.of() : rotatingTabs.stream().distinct().toList();
  }
}
