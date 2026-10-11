# 통계·랭킹 API (B-3·F-7 2단계, stats-v1)

로아와·일로아 통계와 랭킹을 흡수한다. 공식 API에는 통계·랭킹이 없어, 캐릭터 모듈이 관측·수집한 `characters`를 `stats` 모듈이 읽기 전용으로 집계한다. 공식 API를 부르지 않는다. 설계는 볼트 `10 백엔드/04 설계/통계·랭킹 수집 정책 설계 (B-3·F-7).md`, 화면 명세는 `20 프론트엔드/04 설계/통계 화면 명세 (B-3).md`에 있다.

## 모집단

- 대상 캐릭터
  - 프로필(전투력)을 확인했다.
  - 최근 `activeDays`(7·30·90, 기본 30)일 안에 프로필 관측에 **성공**했다(`profile_refreshed_at`). 사용자 검색이나 수집기가 갱신한다.
  - 수집기가 못 찾은 캐릭터(이름 변경·삭제)나 해석에 실패한 캐릭터는 시도 시각(`profile_checked_at`)만 남는다. 그래서 기간이 지나면 모집단과 랭킹에서 빠진다(PM 검토 #100 P1).
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
- 캐시: 조건별로 10분(`stats:v1:{sha256}`). coverage는 조건과 무관하게 따로 10분 캐시한다. 캐시 장애는 조회를 막지 않는다.
- 값 정규화(#108): 범위(itemLevel·combatPower Min/Max)·`width`·`percentileOf`는 소수 둘째 자리로 반올림하고 끝의 0을 지운다. `meta.conditions`도 정규화한 값이다. 자릿수만 바꾼 요청이 캐시 키를 늘리지 못한다.
- 유입 제한: 통계·랭킹은 클라이언트별 분당 60회(`stats` 버킷, [운영 문서](backend-operations.md)).
- 쿼리: 읽기 전용 트랜잭션, 5초 타임아웃. 인덱스는 V7 `idx_characters_stats`(부분 인덱스)와 `idx_characters_roster`다.

## 엔드포인트

| 경로 | 내용 |
|---|---|
| `GET /api/v1/stats/summary` | 아이템 레벨 평균·중앙값, 전투력 중앙값·p90, 딜러·서포터·미판정, 서포터 비율, 보석 평균(보석 기록 30명 이상) |
| `GET /api/v1/stats/classes` | 직업별 인원·비율, 겸용 직업(바드·홀리나이트·도화가·발키리) 서포터 비율, 평균 레벨·보석, 전투력 p10·p25·p50·p75·p90. 인원순 |
| `GET /api/v1/stats/servers` | 서버별 인원·비율, 아이템 레벨·전투력 중앙값 |
| `GET /api/v1/stats/distribution?metric=&width=&percentileOf=` | 히스토그램. 기본 폭은 아이템 레벨 10, 전투력 100이고 최대 300구간이다. 값이 있는 구간만 준다. percentileOf는 순위·상위 %·티어 |
| `GET /api/v1/stats/snapshots` | 현재는 latest만. 일별 06:00 KST 스냅샷은 3단계 |
| `GET /api/v1/rankings?metric=&page=&size=` | 전투력(기본)·아이템 레벨 순위. 동률은 같은 순위(RANK). 페이지 크기 기본 50·최대 100, 최대 10,000위. 공개 제외 목록([public_exclusion](popular-characters.md))을 모집단에서 빼고 순위를 매긴다. `/characters/rank`·`percentileOf`도 같은 모집단이고, 제외된 본인은 `rankVisibility=HIDDEN`이다 |
| `GET /api/v1/characters/rank?characterName=` | 전투력·아이템 레벨 × 전체·서버·직업·서버 내 직업(SERVER_CLASS, scopeValue "서버/직업", #170) 순위. 같은 필터를 받는다. 모집단 밖이면 `included=false`, entries 빈 배열 |

- 상위 %: `rank / total × 100`이고 소수 둘째 자리에서 올림한다. 1위/30명은 3.34다.
- 티어 키: TOP1(≤1%), TOP5, TOP10, TOP25, TOP50, REST. 표시 이름은 프론트가 매핑한다(PM 결정).

| 오류 | 상황 |
|---|---|
| 400 INVALID_REQUEST | 필터 형식·범위, activeDays 값, metric 누락 |
| 400 STATS_INVALID_RANGE | 최솟값 > 최댓값, 랭킹 10,000위 초과 |
| 400 STATS_TOO_MANY_BINS | 구간이 300개를 넘음 |
| 404 STATS_CHARACTER_NOT_OBSERVED | 전투력을 기록한 적 없는 캐릭터(검색하면 기록된다) |
| 503 STATS_UNAVAILABLE | DB 오류 |

## 수집품(내실) 랭킹 (#295)

`GET /api/v1/rankings/collectibles?server=&activeDays=&unit=&page=&size=` — 기본 꺼짐(`ranking.collectibles.enabled`, `RANKING_COLLECTIBLES_ENABLED`). 설계는 볼트 「랭킹 확장 설계 초안」 v1이다.

- 점수: 공식 armory `collectibles`의 Point·MaxPoint만 쓴다. 진행도 = Σ Point / Σ MaxPoint × 100(소수 둘째 자리).
- 단위: 수집품은 원정대가 함께 모으므로 기본은 원정대(`unit=ROSTER`)다.
  - 원정대 행의 `characterName`·`className`·`itemLevel`은 대표(아이템 레벨이 가장 높은 공개 캐릭터)다.
  - `unit=CHARACTER`면 같은 원정대의 공개 캐릭터마다 같은 값으로 한 행씩 나온다.
- 정렬: 진행도 → Σ Point 내림차순이고, 같으면 같은 순위(RANK)다. "먼저 모은 순"은 공식 데이터로 알 수 없어 쓰지 않는다.
- 빠지는 것
  - 하나도 모으지 않은 원정대(Σ Point = 0)
  - `activeDays` 안에 수집품을 관측하지 않은 원정대
  - 공개 제외 캐릭터. 대표·행·캐릭터 수에서 빠지고, 공개 캐릭터가 없는 원정대는 빠진다. 수집품 값은 원정대 공유라 다른 공개 캐릭터가 대표가 된다.
- 표본: `meta`는 다른 통계와 같은 모양이고 `populationUnit`(ROSTER·CHARACTER)이 더해진다(모든 통계 응답에 추가, 기존 엔드포인트는 CHARACTER). 모집단이 30 미만이면 `sampleStatus=COLLECTING`이지만 값·순위는 그대로 준다(참고값, null로 비우는 필드 없음).
- 응답의 `unit`은 문자열이다(값이 늘 수 있음). `types`는 공식 Type 문자열과 공식 순서의 배열이다.
- 저장: 새 표 `roster_collectible`(V13). armory를 받을 때(사용자 검색·수집기 프로필 갱신, 카드 기록과 같은 비동기 작업자) 그 원정대 값을 덮어쓴다.
  - 원정대 키가 아직 없는 캐릭터(원정대를 훑기 전)는 다음 관측에 기록된다.
  - 공식 호출은 늘지 않는다(armory가 이미 collectibles를 받는다).
- 계약 표본: `build/generated/rankings-collectibles.json`(정상, READY), `rankings-collectibles-empty.json`(빈 결과), `rankings-collectibles-collecting.json`(30 미만), `rankings-collectibles-tie.json`(동률).

## 원정대 랭킹 (#297)

`GET /api/v1/rankings/rosters?server=&activeDays=&basis=&aggregate=&page=&size=` — 기본 꺼짐(`ranking.rosters.enabled`, `RANKING_ROSTERS_ENABLED`). 설계는 볼트 「랭킹 확장 설계 초안」 v1 2절이다.

- 원정대: 같은 `roster_key`(수집기가 공식 원정대 목록을 훑을 때 준 키, 서버별)다. 키가 없는 캐릭터는 넣지 않는다.
- 모집단 캐릭터: 검색·프로필 갱신·원정대 확인 중 가장 늦은 관측이 `activeDays` 안인 공개 캐릭터. 공개 제외 캐릭터는 대표·구성·인원·값에서 모두 빠진다.
- 기준(`basis`)
  - `ITEM_LEVEL`(기본): 아이템 레벨(원정대 확인 때 전원 값을 받음)
  - `COMBAT_POWER`: `activeDays` 안에 관측한 전투력만. 아이템 레벨 1640 이상인데 전투력을 관측하지 못한 캐릭터가 있는 원정대는 빼고 `excludedRosters`에 수를 준다(일부만 관측해 평균이 부풀거나 합이 깎이지 않게).
  - `ROSTER_LEVEL`: 원정대 레벨(armory 프로필 ExpeditionLevel, `characters.expedition_level`, V14). 원정대마다 하나라 `aggregate`를 쓰지 않고, 구성은 아이템 레벨 상위다.
- 합산(`aggregate`): `TOP6_AVG`(기본)·`TOP6_SUM`·`ALL_AVG`. 인원이 6명보다 적으면 있는 인원으로 계산하고 `usedCharacters`(화면의 n/6)를 준다.
- 정렬: 값(소수 둘째 자리) 내림차순이고, 같으면 같은 순위(RANK)다.
- 대표: 아이템 레벨이 가장 높은 공개 캐릭터(행의 `characterName`·`className`·`itemLevel`·`combatPower`). `composition`은 기준 상위 최대 6명([{characterName, className, value}])이다.
- 표본: `meta`는 다른 통계와 같은 모양이고 `populationUnit=ROSTER`. 30 미만이면 `sampleStatus=COLLECTING`이지만 값·순위는 그대로 준다.
- 응답의 `basis`·`aggregate`는 문자열이다(값이 늘 수 있음). 요청은 지원 값만 받는다(그 밖은 400).
- 저장: 프로필을 받을 때(사용자 검색·수집기 갱신) `expedition_level`을 갱신하고 값이 없으면 이전 값을 둔다. 공식 호출은 늘지 않는다.
- 계약 표본: `build/generated/rankings-rosters.json`(정상, READY), `rankings-rosters-empty.json`, `rankings-rosters-collecting.json`(30 미만), `rankings-rosters-tie.json`(동률).

## 길드 랭킹 (#301)

`GET /api/v1/rankings/guilds?server=&activeDays=&sort=&page=&size=` — 기본 꺼짐(`ranking.guilds.enabled`, `RANKING_GUILDS_ENABLED`). 설계는 볼트 「랭킹 확장 설계 초안」 v1 3절이다.

- 길드: 같은 서버·같은 길드 이름(공식 프로필 GuildName, `characters.guild_name`, V15)이다.
- 길드원: `activeDays` 안에 프로필을 관측한(검색·수집기 갱신) 공개 캐릭터다. 값은 실제 길드 전체가 아니라 **확인한 길드원 기준**이다.
- 확인한 길드원이 5명 미만인 길드는 빼고 `excludedGuilds`에 수를 준다(PM-백엔드 10-11 결정). `minMembers`로 기준을 알린다.
- 공개 제외는 캐릭터만 뺀다. 길드 자체는 빼지 않는다.
- 정렬: `sort=AVG`(아이템 레벨 평균, 기본) 또는 `SUM`(합). 값(소수 둘째 자리)이 같으면 같은 순위다.
- 행: rank, serverName, guildName, memberCount, itemLevelAvg, itemLevelSum, combatPowerAvg(관측한 길드원만, 없으면 null), combatPowerCount
- 표본: `meta.populationUnit=GUILD`. 30 미만이면 `sampleStatus=COLLECTING`이지만 값·순위는 그대로 준다.
- 저장: 프로필을 받을 때 `guild_name`을 덮어쓴다. 길드를 나가 공식 값이 비면 NULL이 된다. 공식 호출은 늘지 않는다.
- 계약 표본: `build/generated/rankings-guilds.json`(정상, READY), `rankings-guilds-empty.json`, `rankings-guilds-collecting.json`(30 미만), `rankings-guilds-tie.json`(동률).

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
