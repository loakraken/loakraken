package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.ClientLimiter;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.domain.GemSkillCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 보석 직업·스킬 필터: 캐시에 없는 조건 요청은 기능 전용 몫보다 먼저 요청 클라이언트의 GEM_FILTER 버킷에 센다(보안 점검: 한 클라이언트의 몫 독점). */
class GemSkillFilterClientLimitTest {
  private final AuctionPort auctions = mock(AuctionPort.class);
  private final AuctionSearchPort search = mock(AuctionSearchPort.class);
  private final LostArkRequestGate gate = mock(LostArkRequestGate.class);
  private final GemSkillFilterService service =
      new GemSkillFilterService(
          new GemSkillCatalog(
              "test",
              "2026-10-18",
              Map.of("겁화", 500),
              List.of(
                  new GemSkillCatalog.GemClass(
                      "데모닉",
                      List.of(new GemSkillCatalog.GemSkill(27960, "블러드 피어싱", List.of("겁화")))))),
          new GemFilterPolicy(true, 10, 40),
          mock(MarketAuctionService.class),
          auctions,
          search,
          gate,
          Clock.systemUTC(),
          mock(MarketMetrics.class));

  @Test
  void 클라이언트_버킷을_넘으면_기능_몫과_공식_검색_없이_429다() {
    when(auctions.findListings(anyString())).thenReturn(Optional.empty());
    doThrow(new BusinessException(LostArkErrorCode.CLIENT_RATE_LIMITED))
        .when(gate)
        .chargeClient(ClientLimiter.Bucket.GEM_FILTER);
    assertThatThrownBy(() -> service.listings("겁화", 10, 1, "데모닉", 27960))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo(LostArkErrorCode.CLIENT_RATE_LIMITED));
    verify(auctions, never()).acquireGemFilterQuota(anyInt());
    verify(search, never()).search(any());
  }

  @Test
  void 버킷은_기능_몫보다_먼저_세고_캐시에_맞으면_세지_않는다() {
    when(auctions.findListings(anyString())).thenReturn(Optional.empty());
    when(auctions.acquireGemFilterQuota(anyInt())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.listings("겁화", 10, 1, "데모닉", 27960))
        .isInstanceOf(BusinessException.class);
    var order = inOrder(gate, auctions);
    order.verify(gate).chargeClient(ClientLimiter.Bucket.GEM_FILTER);
    order.verify(auctions).acquireGemFilterQuota(10);

    var cachedGate = mock(LostArkRequestGate.class);
    var cachedAuctions = mock(AuctionPort.class);
    when(cachedAuctions.findListings(anyString()))
        .thenReturn(Optional.of(new AuctionPage(0, List.of(), Instant.EPOCH)));
    var cached =
        new GemSkillFilterService(
            new GemSkillCatalog(
                "test",
                "2026-10-18",
                Map.of("겁화", 500),
                List.of(new GemSkillCatalog.GemClass("데모닉", List.of()))),
            new GemFilterPolicy(true, 10, 40),
            mock(MarketAuctionService.class),
            cachedAuctions,
            search,
            cachedGate,
            Clock.systemUTC(),
            mock(MarketMetrics.class));
    cached.listings("겁화", 10, 1, "데모닉", null);
    verify(cachedGate, never()).chargeClient(any());
  }
}
