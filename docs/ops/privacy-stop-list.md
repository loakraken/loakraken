# 처리정지 목록 (운영자 전용)

개인정보 처리정지·삭제 요청(`privacy@`)을 운영자가 직접 반영하는 목록이다(#351, PM 총괄 2026-10-11 21:39·21:41). 공개 접수 화면·엔드포인트는 없다.

- DB 표 이름은 예전 이름 그대로 `public_exclusion`이다(이름 변경 마이그레이션은 위험에 비해 이득이 작아 하지 않음).
- `server_name`을 비우면(NULL) 같은 이름의 모든 서버 캐릭터가 대상이다.

## 목록에 넣으면 생기는 일

| 구분 | 동작 |
|---|---|
| 검색 화면 | 공식 API 실시간 응답을 그대로 보여 준다(5분 캐시 포함) |
| 저장 | 스냅샷·관측 이력·칭호·카드·장비 요약·조회수·인기 검색 기록을 새로 남기지 않는다 |
| 집계 | 통계·랭킹·길드·원정대·수집품 순위·인기 검색에서 빠진다. 통계 수집기도 대상으로 고르지 않는다 |
| 응답 | 처리정지라는 안내·필드는 따로 없다. 이력 탭 등 저장 데이터가 필요한 칸은 기록이 없는 캐릭터와 같이 비어 있다 |

## 넣기

관리자 SSH 또는 Lightsail 브라우저 SSH에서 한 줄씩 실행한다. `이름`·`서버`를 바꾼다(모든 서버면 `'서버'` 대신 `NULL`).

```bash
cd /opt/loakraken/production && C="sudo docker compose -f compose.prod.yaml --env-file stack.env --env-file images.env"
```

목록에 넣고 같은 트랜잭션에서 기존 기록을 지운다. `characters`를 지우면 스냅샷·관측 이력·칭호·카드·장비 요약이 FK CASCADE로 함께 지워진다.

```bash
echo "BEGIN; INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('이름', '서버', 'PRIVACY_STOP'); DELETE FROM characters WHERE character_name = '이름' AND server_name = '서버'; DELETE FROM character_view_daily WHERE character_name = '이름'; DELETE FROM character_popular_hourly WHERE character_name = '이름' AND server_name = '서버'; COMMIT;" | $C exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

- 모든 서버(NULL)로 넣었으면 `DELETE FROM characters`·`character_popular_hourly`의 `AND server_name = '서버'`를 뺀다.
- 조회수(`character_view_daily`)는 이름 단위라 서버 조건이 없다.
- 남은 캐시는 길어야 10분 안에 사라진다(검색 5분, 인기 10분). 바로 지울 필요는 없다.
- 원정대 공유 수집품(`roster_collectible`)은 원정대 단위라 지우지 않는다. 그 캐릭터는 수집품 순위의 대표·행·인원에서 빠진다.

## 확인

```bash
echo "SELECT character_name, server_name, reason_code, created_at FROM public_exclusion ORDER BY created_at DESC LIMIT 20; SELECT COUNT(*) AS characters_left FROM characters WHERE character_name = '이름';" | $C exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

`characters_left`가 0이면 기록이 지워진 것이다.

## 빼기 (처리정지 해제)

```bash
echo "DELETE FROM public_exclusion WHERE character_name = '이름' AND server_name IS NOT DISTINCT FROM '서버';" | $C exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

모든 서버로 넣은 항목이면 `'서버'` 대신 `NULL`을 쓴다. 해제한 뒤 다시 검색되면 그때부터 기록한다(지운 기록은 돌아오지 않는다).

## 처리 기록

넣고 뺀 날짜·요청 메일·실행자를 볼트 `80 배포·운영`에 적는다. 백업에서 복구하면 백업 이후의 처리가 사라지므로 [backup.md](backup.md)의 "복구 시 삭제 요청 다시 적용"을 따른다.
