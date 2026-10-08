# 통계·랭킹 API (B-3·F-7 2단계, stats-v1)

로아와·일로아 통계와 랭킹을 흡수한다. 공식 API에는 통계·랭킹이 없어, 캐릭터 모듈이 관측·수집한 `characters`를 `stats` 모듈이 읽기 전용으로 집계한다. 공식 API를 부르지 않는다. 설계는 볼트 `10 백엔드/04 설계/통계·랭킹 수집 정책 설계 (B-3·F-7).md`, 화면 명세는 `20 프론트엔드/04 설계/통계 화면 명세 (B-3).md`에 있다.

## 모집단

- 대상 캐릭터
  - 프로필(전투력)을 확인했다.
  - 최근 `activeDays`(7·30·90, 기본 30)일 안에 프로필이 갱신됐다. 사용자 검색이나 수집기가 갱신한다.
  - 아이템 레벨 `itemLevelMin`(기본 1640) 이상이다.
- 공식 API에 마지막 접속 시각이 없다. 그래서 "최근 확인"으로 활동을 근사한다. 게임 전체 인구가 아니다.
- 원정대로 발견만 하고 프로필을 아직 갱신하지 않은 캐릭터(전투력·포지션 null)는 모집단에서 뺀다. `coverage.knownCharacters`에만 센다.
- 대표 캐릭터(`representativeOnly`)
  - 같은 서버·같은 원정대 키(`roster_key`)에서 아이템 레벨이 가장 높은 캐릭터다. 동률이면 이름순이다.
  - 대표가 모집단 밖(프로필 없음)이면 그 원정대의 다른 캐릭터도 빠진다.
  - 원정대를 아직 훑지 않은 캐릭터는 자신이 대표다.
- 역할(`role`)은 포지션 판정(position-v1)이다. 판정하지 못한 캐릭터는 `unknownRole`로 센다.
- 보석 평균은 4티어 보석(겁화·작열·광휘)의 캐릭터별 평균 레벨이다. 카드 기록과 함께 armory를 관측할 때 비동기로 저장한다.

## 공통

| 필터 | 값 |
|---|---|
| server, className | 이름(문자·숫자) |
| role | DEALER, SUPPORT |
| activeDays | 7, 30, 90 |
| itemLevelMin/Max | 0~2000 |
| combatPowerMin/Max | 0~100000 |
| representativeOnly | true/false |

- `meta`
  - population, conditions(기본값을 채운 정규화 조건), aggregatedAt, snapshot(latest)
  - ruleVersion(stats-v1), minSample(30), sampleStatus
  - coverage{observedCharacters, knownCharacters, discoveredAccounts}
- 표본이 30명 미만이면 `COLLECTING`이다.
  - 분위수·평균·중앙값·topPercent·tier는 null이다. 인원 수와 순위는 그대로 준다.
  - 직업·서버 행은 행마다 판정한다.
- 캐시: 조건별로 10분(`stats:v1:{sha256}`). 캐시 장애는 조회를 막지 않는다.
- 쿼리: 읽기 전용 트랜잭션, 5초 타임아웃. 인덱스는 V7 `idx_characters_stats`(부분 인덱스)와 `idx_characters_roster`다.

## 엔드포인트

| 경로 | 내용 |
|---|---|
| `GET /api/v1/stats/summary` | 아이템 레벨 평균·중앙값, 전투력 중앙값·p90, 딜러·서포터·미판정, 서포터 비율, 보석 평균(보석 기록 30명 이상) |
| `GET /api/v1/stats/classes` | 직업별 인원·비율, 겸용 직업(바드·홀리나이트·도화가·발키리) 서포터 비율, 평균 레벨·보석, 전투력 p10·p25·p50·p75·p90. 인원순 |
| `GET /api/v1/stats/servers` | 서버별 인원·비율, 아이템 레벨·전투력 중앙값 |
| `GET /api/v1/stats/distribution?metric=&width=&percentileOf=` | 히스토그램. 기본 폭은 아이템 레벨 10, 전투력 100이고 최대 300구간이다. 값이 있는 구간만 준다. percentileOf는 순위·상위 %·티어 |
| `GET /api/v1/stats/snapshots` | 현재는 latest만. 일별 06:00 KST 스냅샷은 3단계 |
| `GET /api/v1/rankings?metric=&page=&size=` | 전투력(기본)·아이템 레벨 순위. 동률은 같은 순위(RANK). 페이지 크기 기본 50·최대 100, 최대 10,000위 |
| `GET /api/v1/characters/rank?characterName=` | 전투력·아이템 레벨 × 전체·서버·직업 순위. 같은 필터를 받는다. 모집단 밖이면 `included=false`, entries 빈 배열 |

- 상위 %: `rank / total × 100`이고 소수 둘째 자리에서 올림한다. 1위/30명은 3.34다.
- 티어 키: TOP1(≤1%), TOP5, TOP10, TOP25, TOP50, REST. 표시 이름은 프론트가 매핑한다(PM 결정).

| 오류 | 상황 |
|---|---|
| 400 INVALID_REQUEST | 필터 형식·범위, activeDays 값, metric 누락 |
| 400 STATS_INVALID_RANGE | 최솟값 > 최댓값, 랭킹 10,000위 초과 |
| 400 STATS_TOO_MANY_BINS | 구간이 300개를 넘음 |
| 404 STATS_CHARACTER_NOT_OBSERVED | 전투력을 기록한 적 없는 캐릭터(검색하면 기록된다) |
| 503 STATS_UNAVAILABLE | DB 오류 |

## 검증 (2026-10-08, 로컬 8081)

- 관측 33명, 원정대 4개, 모집단 30명(activeDays=90): READY
- 전투력 1위 1/30 → 상위 3.34% TOP5
- 언새늘 전투력: 전체 14/30(46.67%, TOP50). 서버(14명)·직업(2명)은 COLLECTING이라 상위 %가 null이다.
- 대표 캐릭터만 보면 4명(원정대 4개)이다.
- 분포 응답 28ms
- 표본은 사용자 검색으로 들어온 원정대에서만 넓어진다([수집기](stats-collector.md)). 운영 초기에는 대부분 COLLECTING이다.

## 다음 단계

- 3단계: 일별 06:00 KST 스냅샷(90일)과 기준일 비교
- 4단계: 채택률(각인, 아크 패시브 노드, 코어)
