-- 장신구 연마 효과 줄 집계(읽기 전용). 연마 수치표 근거 확인용으로 운영 DB에서 주마다 실행해 결과를 내부 기록으로 옮긴다.
-- 캐릭터마다 마지막 관측의 줄만 있으므로 같은 캐릭터를 여러 번 검색해도 늘지 않는다. 처리정지 목록 캐릭터는 뺀다.
-- 실행 예: psql "$DATABASE_URL" -A -F $'\t' -f deploy/sql/polish-tally.sql
SELECT p.slot,
       p.item_level,
       p.item_grade,
       p.option_name,
       p.colour,
       p.option_value,
       COUNT(DISTINCT p.character_id) AS characters,
       MAX(p.observed_at) AS last_observed_at
FROM character_polish_line p
JOIN characters c ON c.character_id = p.character_id
WHERE NOT EXISTS (
    SELECT 1 FROM public_exclusion e
    WHERE e.character_name = c.character_name AND (e.server_name IS NULL OR e.server_name = c.server_name))
GROUP BY p.slot, p.item_level, p.item_grade, p.option_name, p.colour, p.option_value
ORDER BY p.item_level, p.slot, p.option_name, p.colour, p.option_value;
