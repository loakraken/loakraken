-- 시세 알림 웹 푸시(M33). 로그인 없이 브라우저별 익명 구독이다. 계정·이름·이메일·IP·User-Agent는 저장하지 않는다.
-- 구독 해제, 푸시 서비스의 404·410, 오래 쓰지 않은 구독은 행을 지운다(조건은 함께 지워진다).
CREATE TABLE push_subscription (
    subscription_id UUID PRIMARY KEY,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh VARCHAR(128) NOT NULL,
    auth VARCHAR(64) NOT NULL,
    -- 관리 토큰은 SHA-256 해시(16진)만 둔다.
    manage_token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_push_subscription_last_used ON push_subscription (last_used_at);

-- armed: 조건이 지금 거짓이라 다음에 참이 되면 보낼 수 있다. pending_price: 보낼 차례인 가격(발송 대기).
CREATE TABLE price_alert (
    alert_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES push_subscription (subscription_id) ON DELETE CASCADE,
    item_id BIGINT NOT NULL,
    comparator VARCHAR(16) NOT NULL,
    price NUMERIC(14, 2) NOT NULL CHECK (price > 0),
    armed BOOLEAN NOT NULL,
    pending_price NUMERIC(14, 2),
    last_sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_price_alert_subscription ON price_alert (subscription_id);
CREATE INDEX idx_price_alert_item ON price_alert (item_id);
CREATE INDEX idx_price_alert_pending ON price_alert (created_at) WHERE pending_price IS NOT NULL;

-- 알림을 걸 수 있는 품목(예열로 주기적으로 갱신하는 품목)과 마지막으로 관측한 판매 최저가.
CREATE TABLE alert_item_price (
    item_id BIGINT PRIMARY KEY,
    item_name VARCHAR(128) NOT NULL,
    current_min_price NUMERIC(14, 2),
    observed_at TIMESTAMPTZ NOT NULL
);
