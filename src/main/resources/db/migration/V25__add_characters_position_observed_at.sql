-- 포지션(딜러·서포터)을 마지막으로 정한 관측 시각. 관측 기록은 비동기라 늦게 도착한 옛 관측이 새 포지션을 덮지 않게, 이 시각보다 새 관측만 포지션을 바꾼다.
-- 기존 행은 NULL(다음 관측이 채운다).
ALTER TABLE characters ADD COLUMN position_observed_at TIMESTAMPTZ;
