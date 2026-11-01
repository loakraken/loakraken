-- 수집품 항목 사전(V16)의 item_id 시퀀스를 쓰인 최댓값 다음으로 되돌린다.
-- 예전 기록은 아는 항목에도 INSERT ... ON CONFLICT를 써서 충돌 때마다 시퀀스를 하나씩 썼고(SMALLINT, 32767까지),
-- 끝에 닿으면 새 항목뿐 아니라 그 원정대의 수집품 기록 전체가 실패했다. 이제 시퀀스는 처음 보는 이름에만 쓴다.
-- item_id는 겹치지 않고 사이가 비어도 되므로 기존 행·roster_collectible_item.item_ids는 그대로다.
-- 배포 중 아직 도는 이전 인스턴스가 새 이름을 넣는 사이에 최댓값을 읽으면 되돌린 값이 그 id를 다시 줄 수 있으므로,
-- 이 트랜잭션이 끝날 때까지 사전 쓰기를 막는다(읽기는 그대로, 행 435개라 잠금은 짧다).
-- 최댓값이 이미 32767이면 그 값에 머문다. 마이그레이션은 실패하지 않지만, 다음 새 이름은 같은 id라 기본 키 중복으로 실패한다
-- (그때는 item_id를 INTEGER로 넓힌다).
LOCK TABLE collectible_item IN SHARE ROW EXCLUSIVE MODE;
SELECT setval(
    pg_get_serial_sequence('collectible_item', 'item_id'),
    LEAST(COALESCE((SELECT MAX(item_id) FROM collectible_item), 0) + 1, 32767),
    false);
