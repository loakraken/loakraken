-- 수집품 항목 사전(V16)의 item_id 시퀀스를 쓰인 최댓값 다음으로 되돌린다.
-- 예전 기록은 아는 항목에도 INSERT ... ON CONFLICT를 써서 충돌 때마다 시퀀스를 하나씩 썼고(SMALLINT, 32767까지),
-- 끝에 닿으면 새 항목뿐 아니라 그 원정대의 수집품 기록 전체가 실패했다. 이제 시퀀스는 처음 보는 이름에만 쓴다.
-- item_id는 겹치지 않고 사이가 비어도 되므로 기존 행·roster_collectible_item.item_ids는 그대로다.
-- 최댓값이 이미 32767이면 그 값에 머문다(마이그레이션은 실패하지 않고, 새 항목만 계속 들어가지 못한다).
SELECT setval(
    pg_get_serial_sequence('collectible_item', 'item_id'),
    LEAST(COALESCE((SELECT MAX(item_id) FROM collectible_item), 0) + 1, 32767),
    false);
