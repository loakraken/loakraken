package com.loakraken.market.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 아크 그리드 젬 시세(M49 G5). */
@Service
public class ArkGridGemPriceService {
  public ArkGridGemPriceService(MarketBoardService boards) {}

  /** 젬 하나의 1개 즉시 구매 최저가와 관측 시각. */
  public record Price(long gemId, String type, String grade, long unitPrice, Instant observedAt) {}

  public Optional<Price> price(String type, String grade) {
    return Optional.empty();
  }

  public List<Price> prices() {
    return List.of();
  }
}
