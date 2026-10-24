-- 팔찌·젬 실 효율(L38·L39 랭킹의 새 정의). 캐릭터 응답 braceletEfficiency.real·arkGridEfficiency.real과 같은 값을 관측 때 함께 남긴다.
-- 기존 열(전투력 기여 모델)은 랭킹 기준을 바꾸는 설정이 켜질 때까지 그대로 쓰고 채운다. 서포터 팔찌 실 효율은 원본 값이 소수 둘째 자리보다 길어서
-- 자릿수를 제한하지 않는다(캐릭터 화면 값과 같게).
ALTER TABLE characters ADD COLUMN bracelet_real_pct NUMERIC;
ALTER TABLE characters ADD COLUMN bracelet_real_rule_version VARCHAR(40);
ALTER TABLE characters ADD COLUMN ark_grid_real_pct NUMERIC;
ALTER TABLE characters ADD COLUMN ark_grid_real_rule_version VARCHAR(40);

CREATE INDEX idx_characters_bracelet_real_rank
    ON characters (bracelet_real_rule_version, bracelet_real_pct DESC)
    WHERE bracelet_real_pct IS NOT NULL;
CREATE INDEX idx_characters_ark_grid_real_rank
    ON characters (ark_grid_real_rule_version, ark_grid_real_pct DESC)
    WHERE ark_grid_real_pct IS NOT NULL;
