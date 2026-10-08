# 홈 일정·소식 (home-schedule-v1)

프론트 명세 B-4와 합의(Q1~Q3, 2026-10-08)에 따른 홈 위젯 계약이다. 공식 `GET /gamecontents/calendar`, `GET /news/notices`, `GET /news/events`를 캐시해 제공한다. 링크와 이미지는 공식 https 도메인(`lostark.game.onstove.com`, `cdn-lostark.game.onstove.com`)만 통과시키고, 그 밖의 주소는 null로 바꾼다.

## GET /api/v1/home/schedule?date=YYYY-MM-DD

- `date`: KST 날짜. 생략하면 오늘이다.
- 공식 일정은 **이번 주(수요일~다음 주 화요일)** 만 준다. `coveredFrom`·`coveredTo` 밖의 날짜는 빈 목록이다.
- 시각은 공식 KST 지역 시각에 `+09:00`을 붙여 준다.

| 필드 | 내용 |
|---|---|
| `adventureIslands[]` | 그 날 열리는 모험 섬: `name, icon, minItemLevel, startTimes[], rewardKinds[], nextAt` |
| `fieldBosses[]`, `chaosGates[]` | `name, icon, minItemLevel, startTimes[]`(그 날 회차), `nextAt`. 카오스게이트는 지역별 공식 항목을 그대로 준다. |
| `others[]` | 섬·항해·로웬·태초의 섬 등 그 날 회차가 있는 기타 일정: `category`(공식 범주명), `name, icon, minItemLevel, startTimes[], nextAt` |
| `goldIslandDays[]` | 이번 주 골드 회차가 있는 날: `{date, islands[]}` |
| `weekly[]` | 이번 주 날짜별 모험 섬: `{date, islands[{name, rewardKinds}]}` |

### 모험 섬 대표 보상

- 보상은 회차마다 다르다. 공식 보상 항목 자체에 `StartTimes`가 붙어 그 회차에만 나온다(예: 라일라이 아일랜드는 10-12 회차만 골드).
- `rewardKinds`는 그 날 회차를 가리키는 **시간 지정 보상만**으로 판단한다. 시간 지정이 없는 공통 보상(상시 실링 등)은 쓰지 않는다.
- 이름 판별: `골드` → GOLD, `… 카드 팩 …` → CARD_PACK, `실링` → SILLING, `해적 주화`·`대양의 주화 상자` → PIRATE_COIN
- `nextAt`은 서버 현재 시각 이후 첫 회차다. 이번 주에 남은 회차가 없으면 null이다.

## GET /api/v1/home/news?limit=10&category=

- `limit`: 1~50, 기본 10
- `category`: NOTICE(공지), MAINTENANCE(점검), EVENT(이벤트), SHOP(상점). 생략하면 전체다.
- `notices[]{title, category, publishedAt, url, maintenanceDate, maintenanceDateText}`: 최신순이다. 모르는 공식 분류면 `category=null`이다.
  - `maintenanceDate`: 제목에 "점검"이 있고 "N월 N일"(요일 괄호 포함 가능)을 읽을 수 있으면 그 날짜다.
    - 공식 분류가 '공지'인 임시 점검 공지(예: "10월 9일(금) 로스트아크 임시 점검 안내")도 포함한다.
    - 연도는 게시 연도를 쓴다. 연말에 게시된 연초 점검이면 다음 해로 본다.
    - 없는 날짜이거나 읽을 수 없으면 null이다.
  - `maintenanceDateText`: 날짜를 읽은 제목 원문 조각이다.
- `events[]{title, thumbnail, startAt, endAt, rewardAt, url}`: 진행 중인 이벤트만, 종료 임박순이다.

## 호출·캐시·오류

| 대상 | 기본 TTL | 설정 | 환경 변수 | 공유 예산 |
|---|---|---|---|---|
| 일정 | 30분 | `home.cache.calendar-ttl` | `HOME_CALENDAR_CACHE_TTL` | 1회 |
| 소식(공지+이벤트) | 10분 | `home.cache.news-ttl` | `HOME_NEWS_CACHE_TTL` | 2회 |

- Valkey 키: `home:v1:calendar`, `home:v1:news`
- 동시 요청은 합친다.
- 캐시 장애는 `503 HOME_CACHE_UNAVAILABLE`이다.
- 외부 오류는 기존 429/502/503/504를 그대로 쓴다.
- 지표 endpoint 태그: `game_calendar`, `news_notices`, `news_events`
- 잘못된 날짜·limit·분류는 외부 호출 전에 `400 INVALID_REQUEST`다.

## 검증

- 2026-10-08 실서버
  - 이번 주 범위: 10-07~10-14
  - 오늘 모험 섬: 라일라이(해적 주화), 잔혹한 장난감 성(실링), 하모니(카드 팩)
  - 골드 섬: 10-09 스노우팡, 10-12 라일라이. 원시 응답과 일치한다.
  - 기타 일정 19건
  - 점검 공지 `10월 9일(금) … 임시 점검 안내`: 공식 분류는 공지, `maintenanceDate=2026-10-09`
  - 진행 중 이벤트 9건(종료 임박순)
- 테스트
  - 도메인: 시간 지정 보상 판별, nextAt, 주간 표, 점검 날짜(연도 넘김·무효 날짜), 도메인 허용 목록
  - 공식 응답 매핑
  - HTTP: 캐시 재사용, 필터·정렬, 400
  - OpenAPI: 계약 확인. 합성 산출물은 `home-schedule.json`, `home-news.json`이다.
