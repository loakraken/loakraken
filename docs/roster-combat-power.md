# 원정대 캐릭터별 전투력 (L19)

원정대 화면이 전투력 기준으로 정렬하고 식스맨·총합을 전투력으로 보일 수 있도록, 원정대 캐릭터마다 전투력을 준다. 설계는 볼트 `10 백엔드/04 설계/원정대 캐릭터별 전투력 설계 (L19).md`(PM 총괄 10-12 판정 #573)다.

## 조회 `GET /api/v1/characters/roster-combat-power?characterName=`

- 원정대 목록은 원정대 조회(`/siblings`)와 같은 캐시를 쓴다. 캐시가 없을 때만 공식 원정대 API를 1회 부른다.
- 전투력은 우리 DB 기록만 읽는다(공식 호출 0).
  - 확인 시각 = 검색 스냅샷(`last_synced_at`)·프로필 갱신(`profile_refreshed_at`) 중 늦은 값
- 같은 서버 캐릭터만, 공식 원정대 순서로 준다.
- 응답 `data`
  - `items[]`: `characterName`, `serverName`, `className?`, `itemLevel?`(공식 원정대 값), `combatPower?`, `combatPowerObservedAt?`(기록 없으면 null)
  - `missingCount`: 전투력이 null인 캐릭터 수
  - `refreshAvailableAt?`: 불러오기 쿨다운이 끝나는 시각. 쿨다운 중이 아니면 null
- 정렬·식스맨·총합은 화면이 계산한다. 추정값은 없다.
- 처리정지 목록 캐릭터는 기록이 있어도 기록 없는 캐릭터와 같은 모양이다(null). 따로 빼거나 표시하지 않는다(#366 원칙).
- 픽스처: `roster-combat-power.json`

## 불러오기 `POST /api/v1/characters/roster-combat-power/refresh?characterName=`

카드 기록 채우기(`/card-collection/refresh`)와 같은 형태다. 요청 파라미터는 쿼리의 `characterName`이다.

- **대상**: 전투력 기록이 없거나 확인한 지 24시간이 넘은 캐릭터. 아이템 레벨 높은 순으로 요청 1회에 최대 12명이다.
- **공식 호출**: 캐릭터마다 공식 프로필 API `/armories/characters/{name}/profiles`를 1회 부른다.
  - 응답(약 7KB)에 `CombatPower`가 있다(2026-10-12 언새늘로 확인). 전체 armory보다 가볍다.
  - 받은 프로필은 검색과 같은 이력 기록 경로(`CharacterHistoryService.record`)로 저장한다.
  - 처리정지 목록이면 저장하지 않는다(B5).
- **원정대당 한 번**
  - 같은 원정대(서버 + 정렬한 이름 해시) 동시 요청은 single-flight로 한 번에 묶는다.
  - 끝나면 10분 쿨다운이다. 쿨다운 중이면 `409 ROSTER_REFRESH_COOLDOWN` + `Retry-After`다.
- **호출 몫**(B7)
  - 각 호출은 게이트 `withReserve(30)`이다. 창에 30회 넘게 남아 있을 때만 부르므로 사용자 검색 몫이 늘 남는다(N13과 같은 등급).
  - 몫이 모자라거나 공식 장애면 받은 만큼만 저장하고 `200` + `remaining`을 준다.
  - 한 명도 못 불렀으면 쿨다운을 풀고 `refreshAvailableAt`이 null이다(바로 다시 가능).
  - 없는 캐릭터는 건너뛴다.
- **클라이언트 한도**: 이 경로만 클라이언트당 분당 2회다(`lostark.client-limit.roster-refresh-per-minute`, 넘으면 429).
- 응답 `data`: 조회와 같은 `items[]`, `fetched`(이번에 부른 수), `remaining`(남은 대상 수), `refreshAvailableAt?`
- 픽스처: `roster-combat-power-refresh.json`

## 설정 `character.roster-combat-power`

| 키 | 기본값 | 뜻 |
|---|---|---|
| `max-targets` | 12 | 1회 최대 대상 |
| `cooldown` | 10m | 같은 원정대 다시 불러오기까지 |
| `stale-after` | 24h | 이보다 오래된 기록도 다시 부름 |
| `reserve` | 30 | 게이트에서 남길 사용자 몫 |

## 분당 몫

| 호출 주체 | 분당 | 밀려나는 기준 |
|---|---|---|
| 원정대 불러오기(L19) | 요청당 최대 12. 원정대 쿨다운 10분, 클라이언트당 분당 2회 | reserve 30 |

## 지표

`loakraken.character.roster_refresh{outcome=ok|partial|limited|cooldown}`, 공식 호출 `endpoint=PROFILE`
