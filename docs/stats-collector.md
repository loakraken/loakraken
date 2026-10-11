# 통계 표본 수집기 (B-3·F-7 1단계)

통계·랭킹의 모집단을 모은다. 공식 API에는 통계·랭킹이 없고, 검색된 캐릭터만으로는 표본이 작고 치우치기 때문이다. 설계와 프론트 합의는 볼트 `10 백엔드/04 설계/통계·랭킹 수집 정책 설계 (B-3·F-7).md`에 있다.

## 동작

한 회차(`stats.collector.interval`, 기본 3초)마다 공식 호출을 최대 1회 한다. 프로필 갱신과 원정대 발견을 번갈아 하고, 할 일이 없는 쪽은 건너뛴다.

| 작업 | 대상 선정 | 공식 호출 | 저장 |
|---|---|---|---|
| 원정대 발견 | 원정대를 훑은 적이 없거나 7일이 지났거나 원정대 키(roster_key)가 없는 캐릭터 중 아이템 레벨이 높은 순 | siblings 1회 | 계정의 모든 캐릭터를 `characters`에 추가·갱신(서버·직업·아이템 레벨). 같은 원정대 키를 주고 전원을 훑은 것으로 표시 |
| 프로필 갱신 | 1640 이상이고 프로필을 시도한 적 없거나 시도한 지 7일이 지난 캐릭터 중 오래된 순(`profile_checked_at`) | armory 1회 | 관측 이력(전투력 등), 포지션(position-v1), 카드 기록(F-6), 4티어 보석 평균 레벨 |

- 사용자 검색으로 받은 프로필도 갱신으로 친다(`profile_refreshed_at`). 그래서 같은 캐릭터를 중복으로 부르지 않는다.
- 공식 API가 찾지 못한 캐릭터와 해석에 실패한 캐릭터는 시도 시각만 남기고 다음 주기까지 다시 고르지 않는다. 성공 관측 시각(`profile_refreshed_at`)은 바꾸지 않으므로 통계에서 기간이 지나면 빠진다.
- 호출 한도 초과·시간 초과는 다음 회차에 다시 시도한다.

## 예산

- `LostArkRequestGate.backgroundAvailable(reserve)`로 최근 60초 사용량이 `budget − reserve`(90 − 40 = 50) 미만일 때만 호출한다. 사용자 검색이 많으면 자동으로 양보한다.
- 상한은 회차당 1회다. 기본 3초 주기면 분당 20회다.
- API 키가 없거나 `stats.collector.enabled=false`면 동작하지 않는다. `build.gradle` 테스트 태스크에서는 끈다.
- 단일 인스턴스 전제다.

| 설정 | 기본값 | 환경 변수 |
|---|---|---|
| `stats.collector.enabled` | false | `STATS_COLLECTOR_ENABLED` |
| `stats.collector.interval` | 3s | `STATS_COLLECTOR_INTERVAL` |
| `stats.collector.reserve` | 40 | `STATS_COLLECTOR_RESERVE` |
| `stats.collector.min-item-level` | 1640 | — |
| `stats.collector.profile-refresh-after`, `roster-rescan-after` | 7d | — |
| `stats.collector.initial-delay` | 30s | — |

**기본은 꺼져 있다.** 수집을 돌릴 인스턴스 **한 곳**에서만 `STATS_COLLECTOR_ENABLED=true`로 켠다.
- `LostArkRequestGate`는 인스턴스별이라 다른 인스턴스의 사용량을 모른다.
- 같은 키로 여러 인스턴스(팀별 worktree의 8080·8081·8090번대)가 각각 분당 20회를 돌리면 공식 한도를 넘겨, 사용자 검색이 429를 받을 수 있다(PM 검토 #94 P1).

## 시드 목록 (#255)

공식 API에는 캐릭터 목록이 없어서, 표본은 검색된 원정대에서만 넓어진다. 공개 전에는 검색이 없으므로 **시드 이름**으로 시작점을 준다(PM 총괄 10-10 23:25 사용자 지시, 18:20·18:22 승인안).

- 켜져 있으면 수집기가 **시드와 기존 수집(프로필 갱신·원정대 발견)을 번갈아** 처리한다(#271). 시드가 수천 줄이어도 그동안 전투력 표본이 계속 쌓인다. 한쪽에 할 일이 없으면 다른 쪽을 한다.
- 시드 이름 하나당 원정대(siblings)를 1회 훑고, 원정대 전원을 `characters`에 넣는다(원정대 발견과 같은 저장). 그다음 프로필 갱신은 기존 수집기가 한다.
- 공식 호출은 기존 수집과 같은 공유 게이트(`withReserve(reserve)`)와 회차당 1회 상한 안이다.
- 공식 호출 없이 건너뛰는 이름: 공개 제외(`public_exclusion`) 이름, 이미 원정대를 훑은 이름(`roster_scanned_at` 있음).
- 같은 이름은 한 번만 넣는다(공백·유니코드 정규화 후 중복 제거). 재시작해도 이미 훑은 이름은 DB 확인으로 건너뛴다.
- 없는 캐릭터·해석 실패는 다시 부르지 않는다. 호출 한도 초과·시간 초과는 다음 회차에 다시 부른다.
- 이름 말고는 아무것도 보관하지 않는다. 로그에는 이름이 아니라 개수만 남는다.

| 설정 | 기본값 | 환경 변수 |
|---|---|---|
| `stats.seed.enabled` | false | `STATS_SEED_ENABLED` |
| `stats.seed.names` | 빈 목록 | `STATS_SEED_NAMES`(쉼표 구분) |
| `stats.seed.file` | 빈 값 | `STATS_SEED_FILE`(한 줄에 한 이름, `#` 주석). 운영 compose는 스택 디렉터리 `seed/`를 `/seed`로 읽기 전용 마운트하므로 `seed/names.txt`에 두고 `/seed/names.txt`를 쓴다 |

**시드 이름은 저장소에 커밋하지 않는다.** 운영 서버의 `stack.env`(`STATS_SEED_NAMES`) 또는 스택 디렉터리 `seed/` 파일에만 둔다. 이름이 많으면 파일이 낫다.

- 시드 목록은 **기동 때 한 번** 읽는다. 이름을 더하거나 바꾼 뒤에는 api를 다시 만든다(`docker compose up -d --wait --force-recreate api`. 시드 파일 내용만 바꾸면 설정이 같아 `up -d api`로는 다시 만들어지지 않는다). 이미 원정대를 훑은 이름은 다시 부르지 않으므로 목록을 통째로 다시 넣어도 된다.
- env와 파일은 합쳐서 중복을 뺀다.

시드 범위(처리방침 2항, 공식 API가 공개하는 게임 정보만): 개발 DB에 있는 공개 캐릭터의 원정대 대표 1명씩과 운영자·지인 계정(PM 총괄 10-10 23:25).

지표
- `loakraken.stats.seed{outcome=success|skipped|failed}`: 처리한 시드 수. 한도 초과로 미룬 시드는 `loakraken.stats.collector{task=seed}`에만 남는다.
- `loakraken.stats.seed.pending`: 남은 시드 수(게이지)
- 발견 수는 기존 `loakraken.stats.collector.discovered`에 더한다.

### 공개 전 수집 속도

`STATS_COLLECTOR_INTERVAL`·`STATS_COLLECTOR_RESERVE`는 운영 compose가 컨테이너로 넘긴다(#255부터). PM-백엔드 판정(10-10 23:26): 공개 전에는 `1s`·`25`로 한시 변경을 허용하고, **도메인 공개 전에 `3s`·`40`으로 되돌린다.** 1초면 수집기 최대 분당 약 60회이고, 시세 예열 15회를 더해도 공유 게이트 90(공식 100) 안이다.

## 수집 상태 확인

- 지표: 관리 포트(로컬 9091) `/actuator/metrics/loakraken.stats.collector`
  - 태그 `task`·`outcome`으로 회차를 나눠 본다.
  - `yielded`가 많으면 사용자 요청 때문에 양보하는 중이다.
  - `task=none`만 늘면 할 일이 없는 상태다. 알고 있는 원정대를 모두 7일 안에 훑었다는 뜻이다.
- DB: `SELECT COUNT(*) FILTER (WHERE combat_power IS NOT NULL), COUNT(DISTINCT roster_key) FROM characters`
- 표본은 사용자 검색으로 들어온 원정대에서만 넓어진다. 공식 API에는 랭킹이나 캐릭터 목록이 없다.

## 저장 (Flyway V5)

- `characters`에 추가한 열
  - `position`: DEALER, SUPPORT, 또는 null
  - `profile_refreshed_at`
  - `roster_scanned_at`
  - `discovered_at`: 원정대로 발견한 시각
- 원정대로 발견한 캐릭터는 프로필 갱신 전까지 전투력·포지션이 비어 있다.

## 지표

- `loakraken.stats.collector{task=profile|roster|none, outcome=success|not_found|failed|yielded|storage_failed}`
- `loakraken.stats.collector.discovered`: 새로 발견한 캐릭터 수

## 검증

- 2026-10-08 실서버에서 2분 실행
  - 캐릭터 4 → 46명(발견 42, 프로필 갱신 30, 포지션 26)
  - 분당 약 20회
  - 수집 중 사용자 검색 200, 429 0건
- 단위: 양보·키 없음·끔, 프로필 → 원정대 교대, 없는 캐릭터 표시, 한도 초과 재시도, 포지션 미판정 null
- 저장소(Testcontainers): 발견 수, 원정대 갱신, 대상 우선순위, 검색 이력의 갱신 반영
- 게이트: 사용자 몫 유지와 한도 대기 중 양보
