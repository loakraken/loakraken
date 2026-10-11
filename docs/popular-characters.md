# 인기 검색 캐릭터 (F-10, popular-v1)

로아와·일로아 홈의 인기 캐릭터를 흡수한다. PM 총괄 결정(2026-10-08)에 따라 노출하고, 제외 목록을 운영한다. 공식 API를 부르지 않는다.

## 순위 기준

- 캐릭터 검색 API(`/characters/search`)가 성공할 때마다 고유 조회를 센다.
  - 조회수(F-8, `/characters/views`, 반복 포함)와는 따로 센다. F-8 계약과 "반복 조회 포함" 문구는 그대로다.
- 같은 클라이언트·같은 캐릭터는 KST 하루에 1회만 센다.
  - 키는 `HMAC-SHA256(일별 salt, 정규화한 IP + 서버 + 캐릭터)` 앞 16바이트다.
  - IP 정규화(`ClientAddresses`, #108 유입 제한과 공통): IPv4는 그대로, IPv6는 /64 접두사, IPv4-mapped는 IPv4. 한 사용자가 받는 /64 안에서 주소를 바꿔 가며 우회하는 것을 막는다.
  - salt는 메모리에만 두고 날마다 새로 만든다. 저장하거나 로그에 남기지 않는다.
  - 원본 IP는 어디에도 저장하지 않는다.
  - 키는 Valkey `popular:v1:seen:{KST 날짜}` 집합에 넣고 26시간 뒤 만료된다.
- 고유 조회는 UTC 시간 버킷(`character_popular_hourly`)에 모은다.
  - 24h: 현재 시각이 속한 버킷을 포함한 최근 24개 버킷
  - 7d: 최근 168개 버킷
- 기간 합계 고유 조회가 `minUniqueViews` 이상인 캐릭터만 올린다. 기준은 기간별로 24h 5회, 7d 15회다(`character.popular.min-unique-views.day`·`.week`).
  - 하루 1회 중복 제거라 7d는 한 클라이언트가 매일 검색하면 혼자 5회를 넘길 수 있다. 그래서 기준을 높였다. 운영하며 조정한다(PM-백엔드 #112 P2).
  - 정렬은 고유 조회 수 내림차순, 최근 조회 버킷, 이름순이다.
- 응답에는 조회 수 숫자를 넣지 않는다.
- 기간 전체 고유 조회가 `minSample`(기본 30) 미만이면 `sampleStatus=COLLECTING`이고 목록을 비운다.
- 결과는 Valkey `popular:v1:result:{기간}:{개수}`에 10분 캐시한다.
- 시간 버킷은 8일 보관하고 1시간마다 정리한다.

## 응답 `GET /api/v1/characters/popular?period=24h|7d&limit=10`

```json
{"period":"24h","aggregatedAt":"…","sampleStatus":"READY","minUniqueViews":5,"ruleVersion":"popular-v1",
 "items":[{"rank":1,"characterName":"…","serverName":"…","className":"바드","itemLevel":1780.00}]}
```

- className·itemLevel은 `characters`의 최근 관측값이고, 없으면 null이다.
- 오류
  - 400 INVALID_REQUEST: period, limit 1~50
  - 503 CHARACTER_POPULAR_DISABLED, CHARACTER_POPULAR_UNAVAILABLE

## 클라이언트 IP

- `server.forward-headers-strategy: native`(Tomcat RemoteIpValve)를 쓴다.
- 직접 연결한 주소가 신뢰 프록시일 때만 `X-Forwarded-For`를 원격 주소로 쓴다.
  - 신뢰 프록시 기본 범위: 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, 100.64/10, ::1, fc00::/7
  - 외부에서 직접 보낸 헤더는 무시한다.
- 운영 구성: 인터넷 → Caddy(외부 XFF를 덮어씀) → Next(헤더 전달) → 백엔드(Docker 브리지). 그래서 별도 설정이 필요 없다.
- 한계(#107 공유 salt 검토 때 함께 처리)
  - 인스턴스마다 salt가 다르다. 그래서 여러 인스턴스를 오가는 같은 클라이언트는 하루 2회 이상 셀 수 있다(단일 인스턴스 전제).
  - 재시작하면 salt를 새로 만들기 때문에, 같은 날 같은 클라이언트를 다시 센다.
- **운영은 반드시 `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`로 신뢰 프록시를 좁힌다.** 기본값은 사설 대역 전체이고, 운영 compose(#111)는 web 1곳으로 좁힌다.

## 처리정지 목록 `public_exclusion`

운영자 전용 목록이다(표 이름은 예전 이름). 넣고 빼는 방법과 검색 때 기록을 남기지 않는 범위는 [ops/privacy-stop-list.md](ops/privacy-stop-list.md)를 본다(#351).

개인 순위·백분위의 모집단에서 완전히 뺀다(PM 총괄 결정 2026-10-09). 그래서 목록 순위와 각자 페이지의 순위가 항상 같고, 순위 번호에 빈칸이 생기지 않는다.

| 적용 | 대상 |
|---|---|
| 모집단에서 뺌 | 인기 검색, `/rankings`, `/characters/rank`(standing·contains), `/stats/distribution`의 `percentileOf` |
| 그대로 | summary·classes·servers·히스토그램(개인을 드러내지 않는 집계) |

- 목록의 본인을 `/characters/rank`로 조회하면 관측이 없는 캐릭터와 같은 404(`STATS_CHARACTER_NOT_OBSERVED`)다(#363). 목록에 있다는 사실을 응답으로 드러내지 않는다. `rankVisibility=HIDDEN`은 더 이상 쓰지 않는다.
- 등록·해제는 다음 집계 주기(캐시 10분)부터 반영된다.

| 열 | 뜻 |
|---|---|
| character_name | 캐릭터명 |
| server_name | 서버명. NULL이면 모든 서버 |
| created_at | 등록 시각 |
| reason_code | 사유 코드(예: REQUEST 본인 요청, ABUSE 조작 의심, POLICY 운영 정책) |

등록·해제는 운영자가 수동으로 한다. 공개 API는 없다. 명령은 [ops/privacy-stop-list.md](ops/privacy-stop-list.md)만 쓴다(넣을 때 기존 기록도 같은 트랜잭션에서 지운다. 표에 INSERT만 하면 기존 기록이 남는다).

결과 캐시(인기 10분, 랭킹 10분)가 지나야 반영된다.

## 지표

- `loakraken.character.popular.writes{outcome}`: 비동기 기록 결과. 대기열이 차서 버린 조회도 failure로 센다.
