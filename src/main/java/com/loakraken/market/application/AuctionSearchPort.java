package com.loakraken.market.application;

/** 공식 경매장 검색(`POST /auctions/items`). 즉시 구매가 오름차순 1페이지(10개). */
public interface AuctionSearchPort {
  AuctionPort.AuctionPage search(AuctionPort.AuctionQuery query);
}
