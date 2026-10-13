-- 거래소 분 단위 차트(K35). 시세 예열이 이미 받은 가격을 관측 시각의 UTC 분 칸에 둔다(공식 추가 호출 없음). 2시간만 보관한다.
-- sample_every_seconds: 그 품목을 다시 받는 예열 주기(보드 30초·각인서 30분·재련 재료 10분 등). 분 단위가 아닌 품목을 알린다.
CREATE TABLE market_price_minute (
    item_id BIGINT NOT NULL,
    minute TIMESTAMPTZ NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    current_min_price NUMERIC(14, 2),
    recent_price NUMERIC(14, 2),
    sample_every_seconds INTEGER NOT NULL CHECK (sample_every_seconds > 0),
    PRIMARY KEY (item_id, minute)
);

CREATE INDEX idx_market_price_minute_minute ON market_price_minute (minute);
