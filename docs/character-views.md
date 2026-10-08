# 캐릭터 조회수 (F-8)

일로아 소셜 패널(최근 16주 일별 조회 히트맵, 조회수 합계)에 대응한다. **LOAKraken에서 그 캐릭터를 검색한 횟수**이며, 게임이나 다른 사이트의 조회 수가 아니다.

## 집계 규칙

- `GET /api/v1/characters/search` 응답이 성공할 때마다 공식 캐릭터명(응답의 `characterName`)과 KST 날짜별로 1을 더한다.
  - 원정대 포함 여부는 상관없다.
  - 치명타·공이속 계산처럼 다른 API가 내부에서 부르는 조회는 세지 않는다.
- 조회한 사람(IP·세션·계정)은 저장하지 않는다. 그래서 같은 사람의 반복 조회도 센다.
- 응답 경로 밖에서 단일 작업자가 비동기로 기록한다(UPSERT `view_count + 1`).
  - DB가 느리거나 끊겨도 검색은 지연되지 않는다.
  - 대기열 1,000건이 차거나 저장에 실패하면 그 조회는 세지 않는다. 근사 집계다.
- 테이블은 `character_view_daily(character_name, view_date, view_count)`다(Flyway V4).
- 정책은 `character.views.enabled`(기본 true, `CHARACTER_VIEWS_ENABLED`)다. 끄면 기록하지 않고 조회 API는 `503 CHARACTER_VIEWS_DISABLED`를 준다.
- 지표: `loakraken.character.views.writes{outcome=success|failure}`. failure에는 대기열 초과로 버린 조회도 포함된다.

## GET /api/v1/characters/views?characterName=&days=112

- `days`: 1~366, 기본 112(16주). 외부 호출은 없다.
- 응답 `{characterName, timezone, from, to, days[{date, count}], rangeTotal, total}`
  - `days`는 from~to의 모든 날짜다. 오래된 날부터이고 조회가 없는 날은 0이다.
  - `total`은 집계를 시작한 뒤의 전체 합계다.
- 잘못된 이름·일수는 `400 INVALID_REQUEST`, 저장소 장애는 `503 CHARACTER_VIEWS_UNAVAILABLE`이다.

## 검증

- 단위: KST 날짜 경계(UTC 15:30 → 다음 날), 비동기 기록과 실패 격리·지표, 비활성 정책, 0 채움과 합계
- HTTP: 검색 2회 → 오늘 2, 계산 API의 내부 조회는 0, 112일 채움, 400. 합성 산출물은 `character-views.json`이다.
- OpenAPI 계약
