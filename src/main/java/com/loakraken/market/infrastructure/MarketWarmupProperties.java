package com.loakraken.market.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 핵심 품목 14일 이력 예열. 기본 목록은 프론트 합의(B-5 Q3, 2026-10-08)의 19개 품목 공식 ID다.
 *
 * <p>interval은 이력 TTL(10분)보다 짧아야 보드의 7일 값이 끊기지 않는다.
 *
 * @param boardInterval 기본 보드(boardTabs) 강제 갱신 주기. 가격 캐시 TTL(30초)과 같게 두면 사용자가 공식 호출을 기다리지 않는다
 * @param boardTabs 기본 보드를 갱신할 탭. 홈 요약과 시세 기본 탭이 쓰는 REFINING 하나다(프론트 회신 10-09)
 * @param maxCallsPerMinute 예열 전체(이력·보드)의 분당 공식 호출 상한. 넘으면 그 분의 나머지 예열을 건너뛴다
 */
@ConfigurationProperties("market.warmup")
public record MarketWarmupProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("9m") Duration interval,
    @DefaultValue("20s") Duration initialDelay,
    @DefaultValue("30s") Duration boardInterval,
    @DefaultValue("REFINING") List<com.loakraken.market.domain.MarketTab> boardTabs,
    @DefaultValue("15") int maxCallsPerMinute,
    @DefaultValue({
          // T4 재련 재료: 아비도스·상급 아비도스, 파괴석·결정, 수호석·결정, 돌파석·위대한, 파편 주머니 소·중·대
          "6861012",
          "6861013",
          "66102006",
          "66102007",
          "66102106",
          "66102107",
          "66110225",
          "66110226",
          "66130141",
          "66130142",
          "66130143",
          // 숨결: 용암·빙하
          "66111131",
          "66111132",
          // 업화 책: 야금술 [11-14]·[15-18]·[19-20], 재봉술 [11-14]·[15-18]·[19-20]
          "66112543",
          "66112551",
          "66112553",
          "66112546",
          "66112552",
          "66112554"
        })
        List<Long> itemIds) {
  public MarketWarmupProperties {
    if (interval == null || interval.isZero() || interval.isNegative())
      throw new IllegalArgumentException("예열 주기는 양수여야 합니다.");
    if (initialDelay == null || initialDelay.isNegative())
      throw new IllegalArgumentException("예열 시작 지연은 0 이상이어야 합니다.");
    if (boardInterval == null || boardInterval.isZero() || boardInterval.isNegative())
      throw new IllegalArgumentException("보드 예열 주기는 양수여야 합니다.");
    if (maxCallsPerMinute < 1) throw new IllegalArgumentException("예열 분당 상한은 1 이상이어야 합니다.");
    boardTabs = boardTabs == null ? List.of() : boardTabs.stream().distinct().toList();
    itemIds = itemIds == null ? List.of() : itemIds.stream().distinct().toList();
    if (itemIds.stream().anyMatch(id -> id == null || id < 1))
      throw new IllegalArgumentException("예열 상품 ID는 양수여야 합니다.");
  }
}
