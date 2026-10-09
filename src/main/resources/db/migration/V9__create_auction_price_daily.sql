-- 경매장 시세 표(auction-board-v1)의 칸별 KST 일 마지막 관측 최저가. 공식 API에 경매장 이력이 없어 전일 대비·추이를 우리 관측으로 만든다.
-- cell_key는 검색 조건(카테고리·등급·이름·품질·연마 효과)의 해시다.
CREATE TABLE auction_price_daily (
    cell_key VARCHAR(64) NOT NULL,
    kst_date DATE NOT NULL,
    min_buy_price BIGINT NOT NULL CHECK (min_buy_price > 0),
    total_count BIGINT NOT NULL CHECK (total_count >= 0),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (cell_key, kst_date)
);
