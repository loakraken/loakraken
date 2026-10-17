-- 팔찌 효율·젬 효율(L38·L39 랭킹). armory를 관측할 때 검색 응답과 같은 모델로 계산해, 계산된(OK) 값만 남긴다.
-- 계산 모델이 바뀌면 저장값이 낡으므로 규칙 버전을 함께 남기고, 랭킹은 지금 규칙 버전 값만 센다.
ALTER TABLE characters ADD COLUMN bracelet_efficiency_pct NUMERIC(7, 2);
ALTER TABLE characters ADD COLUMN bracelet_rule_version VARCHAR(40);
ALTER TABLE characters ADD COLUMN ark_grid_gem_pct NUMERIC(7, 2);
ALTER TABLE characters ADD COLUMN ark_grid_rule_version VARCHAR(40);
ALTER TABLE characters ADD COLUMN efficiency_observed_at TIMESTAMPTZ;

CREATE INDEX idx_characters_bracelet_rank
    ON characters (bracelet_rule_version, bracelet_efficiency_pct DESC)
    WHERE bracelet_efficiency_pct IS NOT NULL;
CREATE INDEX idx_characters_ark_grid_gem_rank
    ON characters (ark_grid_rule_version, ark_grid_gem_pct DESC)
    WHERE ark_grid_gem_pct IS NOT NULL;
