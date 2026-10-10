-- 패치 이력 1단계(patch-notice-v1). 공식 /news/notices의 패치 공지(제목 규칙)만 공지 번호로 쌓는다. 본문은 저장하지 않는다.
-- 공식 API는 최근 약 3개월만 주므로, 관측할 때마다 쌓아 이력을 늘린다.
CREATE TABLE patch_notice (
    notice_id BIGINT PRIMARY KEY,
    title VARCHAR(300) NOT NULL,
    category VARCHAR(32) NOT NULL,
    published_at TIMESTAMP NOT NULL,
    url VARCHAR(300) NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_patch_notice_published ON patch_notice (published_at DESC, notice_id DESC);
CREATE INDEX idx_patch_notice_category ON patch_notice (category, published_at DESC);
