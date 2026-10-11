-- 수집품 항목별 보유율(#305, 볼트 「수집품 순위·보유율 설계 메모」 2절).
-- collectible_item: 공식 collectibles의 CollectiblePoints 항목 사전. 관측 때 모르는 (종류, 이름)만 더하고, 순서·만점이 바뀌면 갱신한다.
-- roster_collectible_item: 원정대마다 한 행. 가진 항목(Point > 0)만 (item_id, point) 평행 배열로 둔다(원정대당 약 1KB).
--   모코코 씨앗은 지역별 개수라 비트 대신 point를 둔다. item_id는 공식 순서가 바뀌어도 그대로라 예전 행이 어긋나지 않는다.
CREATE TABLE collectible_item (
    item_id      SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type         VARCHAR(32)  NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    sort_order   SMALLINT     NOT NULL,
    max_point    SMALLINT     NOT NULL,
    last_seen_at TIMESTAMPTZ  NOT NULL,
    UNIQUE (type, name)
);

CREATE TABLE roster_collectible_item (
    roster_key  UUID         PRIMARY KEY,
    item_ids    SMALLINT[]   NOT NULL,
    points      SMALLINT[]   NOT NULL,
    observed_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_roster_collectible_item_observed ON roster_collectible_item (observed_at);
