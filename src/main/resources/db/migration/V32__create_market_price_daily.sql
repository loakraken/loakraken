-- 거래소 품목 일별 시세. 공식 상품 상세 Stats(오늘 포함 최근 14일)를 이미 받을 때마다(상세 화면·시세 이력 예열·거래량 예열) 완료일(KST 오늘 이전)을
-- 덮어쓴다(새 공식 호출 없음). 첫날 14일이 채워지고, 차트 기간(7일~1년)을 위해 지우지 않는다.
-- 같은 상품 ID의 변형(거래 가능 횟수·묶음 수)은 따로 둔다. 공식 값이 없으면 -1.
CREATE TABLE market_price_daily (
    item_id BIGINT NOT NULL,
    trade_remain_key INTEGER NOT NULL,
    bundle_key INTEGER NOT NULL,
    kst_date DATE NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    avg_price NUMERIC(16, 4) NOT NULL CHECK (avg_price >= 0),
    trade_count BIGINT NOT NULL CHECK (trade_count >= 0),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (item_id, trade_remain_key, bundle_key, kst_date)
);
