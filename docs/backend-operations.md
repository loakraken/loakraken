# 백엔드 MVP 운영과 지표


## 공유 호출 게이트와 리더 임대 (#107)

같은 공식 키를 쓰는 모든 인스턴스가 분당 90회를 **합산**으로 지킨다.

- 저장소: `lostark.gate.store=valkey`(기본) 또는 `memory`(단일 인스턴스)
  - Valkey 키: `lostark:gate:{keyId}:window`(정렬 집합), `lostark:gate:{keyId}:blocked-until`
  - 판정과 기록은 Lua 한 번이다. 서버 TIME 기준으로 대기 확인 → 60초 밖 정리 → ZCARD 판정 → ZADD·PEXPIRE 순서다.
  - 공식 429와 잔여 0 헤더의 대기 시각은 더 늦을 때만 바꾼다. 그래서 한 인스턴스가 받은 429를 모두가 기다린다.
- `keyId`
  - 기본은 API 키 SHA-256 앞 12자다. 키 원문은 Valkey에 넣지 않는다.
  - 운영·스테이징은 `LOSTARK_GATE_KEY_ID`(`lostark.gate.key-id`)로 명시한다.
- 동시 요청 4개는 인스턴스 안에서만 센다.
- 백그라운드(통계 수집기·시세 예열·경매 칸 채우기)는 `gate.withReserve(40, …)` 안에서 호출한다. reserve는 ThreadLocal이라 **같은 스레드 전용**이다(비동기로 넘기면 그 스레드에서 다시 감싼다).
- 사용자 요청이 백그라운드가 시작한 단일 비행에 합류해 백그라운드의 한도 거절을 받는 경우(#130 P2 ②)는 지금 없다: 시세 예열은 `refreshItemHistory`·`refreshSearch`로 단일 비행을 거치지 않고, 수집기는 공식 포트를 직접 부른다. 앞으로 백그라운드가 단일 비행을 쓰게 되면 사용자 요청을 합류시키지 않는다.
- 시계 차이(#130 P2 ③): 대기 시각(blocked-until)은 인스턴스 시계, 60초 창은 Valkey 서버 TIME이다. 인스턴스 시계가 어긋난 만큼 공식 429 뒤 대기 시간이 길거나 짧아질 수 있다(NTP 동기 전제, 기록만).
  - 그 안의 acquire()가 사용자 몫(budget − reserve = 50)을 남기는지 판정과 기록을 한 번에 한다.
  - 다른 인스턴스가 끼어들 틈이 없다.
- Valkey 장애
  - 게이트 전용 연결의 명령 타임아웃은 100ms다(`lostark.gate.command-timeout`).
  - 사용자 요청은 `503 LOST_ARK_GATE_UNAVAILABLE`로 거절한다. 캐시 503 정책과 같다.
  - 백그라운드는 실행하지 않는다.
  - 지표 이벤트는 `gate_unavailable`이다.
- 리더 임대: `lostark:leader:stats-collector`(30초), `lostark:leader:market-warmup`(주기 + 1분)
  - 얻기는 `SET NX PX`다. 연장·해제는 "값이 내 인스턴스 ID일 때만" Lua로 한다.
  - 못 얻으면 수집기 지표에 `outcome=not_leader`가 남는다.
- 백그라운드 합산 상한: 수집기 20/분 + 예열(이력 9분에 19회 + 기본 보드 30초마다 1회, 합산 15/분 상한, #154) ≤ budget − reserve(50)

## 클라이언트별 유입 제한 (#108)

공유 게이트가 공식 키 전체(90회/분)를 지킨다면, 유입 제한은 **한 사람이 그 예산을 혼자 쓰지 못하게** 한다.

- 클라이언트: Tomcat RemoteIpValve가 신뢰 프록시(웹)의 `X-Forwarded-For`로 정한 원격 주소를 `ClientAddresses`로 정규화한다(IPv6는 /64).
  - Valkey 키에는 주소의 HMAC-SHA256 앞 16바이트만 넣는다. HMAC 키는 공식 API 키에서 파생해 같은 키를 쓰는 인스턴스끼리 같다.
  - 원격 주소가 루프백·사설망이면(헤더가 빠진 내부 요청) 세지 않고 `loakraken.client.limit.unattributed`만 올린다(`exempt-internal`).
- 버킷(1분 고정 창, `lostark:client-limit:{bucket}:{client}:{분}`, INCR·PEXPIRE Lua 한 번)

| 버킷 | 기본(분당) | 세는 것 |
|---|---|---|
| official | 20 | 요청이 **실제로 일으킨** 공식 호출. `LostArkRequestGate.acquire()`에서 공유 예산보다 먼저 센다. 캐시 적중·단일 비행 합류·수집기·예열과 `withReserve` 안의 호출(경매 칸 채우기처럼 사용자 몫을 남기는 쪽으로 이미 제한된 호출)은 세지 않는다 |
| stats | 60 | `/stats/**`·`/rankings`·`/characters/rank` 요청(DB 집계) |
| general | 240 | `/api/v1/**` 전체 요청 |
| bot | 120 | `X-Loakraken-Bot: 1` 요청 전체(모든 봇 주소 합산). general보다 클 수 없다(봇 표시는 User-Agent라 흉내 낼 수 있음). 통계 경로는 봇 공유 stats(60)도 세고, 봇의 공식 호출도 official 하나를 같이 쓴다 |

- 거절: `429 CLIENT_RATE_LIMITED`. 요청 버킷 거절에는 `Retry-After`(창 끝까지 초)를 준다. 공식 호출 한도 거절은 요청 도중이라 헤더 없이 같은 코드다(#138의 Retry-After 공통 처리 병합 후 붙인다).
  - 공유 예산 초과(`LOST_ARK_RATE_LIMITED`)와 코드가 다르다. 프론트는 둘 다 "잠시 후 다시"로 보여 주면 된다.
- 봇 표시: 웹이 User-Agent로 검색봇을 판단해 `X-Loakraken-Bot: 1`을 붙인다. 백엔드는 User-Agent를 보지 않는다. **웹은 밖에서 들어온 같은 헤더를 지워야 한다.**
- Valkey 장애면 제한하지 않는다(`event=unavailable`). 공식 호출은 공유 게이트가 503으로 막는다. 게이트 전용 연결(100ms)을 같이 쓴다.
- 설정: `lostark.client-limit.*` / `CLIENT_LIMIT_ENABLED·_OFFICIAL·_STATS·_GENERAL·_BOT·_EXEMPT_INTERNAL`
- 지표: `loakraken.client.limit{bucket, event=reject|unavailable}`, `loakraken.client.limit.unattributed`
- 기본값 근거: 캐릭터 페이지 하나는 공식 호출 1~2회(전투정보·원정대, 원정대는 칭호·카드와 캐시 공유)라 official 20은 새 캐릭터 10명 이상/분이다. 일반 240은 페이지당 5~8개 요청으로 30페이지 이상/분이다.

### 없는 캐릭터 캐시

- 공식 API가 "없다"고 답한 이름(전투정보 null·원정대 빈 목록·공식 404)만 `character:v1:{이름}:missing`에 5분 기억한다. 그 사이 같은 이름은 공식 호출 없이 `404 CHARACTER_NOT_FOUND`다.
- 한도(429)·타임아웃·서버 오류는 기억하지 않는다.
- 새로 만든 캐릭터는 최대 5분 늦게 보일 수 있다. 캐시 읽기 장애면 정상 경로로 조회한다.

## 실행 전제

MVP는 **동일 API 키를 사용하는 백엔드 프로세스 1개, replicas=1**을 전제로 한다. 배포 인프라를 새로 만들거나 replica 수를 자동 강제하는 코드를 추가한 것은 아니다. 현재 single flight, 호출 예산, 동시성 제한은 JVM 내부 상태다.

기본 외부 호출 예산은 인스턴스당 90회/분, 동시 요청 4개이며 공식 100회/분 한도에 여유를 둔다. 캐릭터와 거래소는 동일 제한기 빈을 사용하며 합산 예산이다. 캐릭터 검색 캐시 미적중은 전투정보·원정대 요청 2회(`includeSiblings=false`면 전투정보 1회), 원정대 단독 조회는 1회, 거래소 한 페이지 조회는 1회를 사용할 수 있다. 배치·로컬 테스트·다른 서비스가 같은 키를 사용하면 총량을 공유한다. 단일 replica만으로 다른 프로세스까지 제어되지는 않는다.

운영 시 동일 키를 쓰는 프로세스를 함께 관리하고, 배포 중 구·신 인스턴스를 겹쳐 실행하지 않는다. 재시작은 JVM 예산 기록을 초기화하므로 필요 시 기존 요청 구간이 끝날 때까지 기다린다. 여러 인스턴스가 필요하면 배포 전에 공유 quota와 갱신 조정을 설계한다. 현재 문서는 분산 제한을 구현했다는 의미가 아니다.

Valkey 읽기·쓰기 장애는 `503 CHARACTER_CACHE_UNAVAILABLE`로 종료한다. 캐시 장애를 미적중처럼 취급하거나 외부 API로 자동 우회하지 않는다. 손상된 JSON은 제거한 뒤 재조회하지만 삭제 자체가 실패하면 503이다. 외부 API는 자동 재시도하지 않는다.

관측 저장·이력 공개는 `CHARACTER_SNAPSHOTS_ENABLED=false`를 유지한다. 저장 범위 확인 전 diff·장기 통계·성장 그래프를 확장하지 않는다.

거래소는 가격 30초·검색 옵션 10분을 기본 TTL로 사용한다. 핵심 19개 품목 이력은 9분마다 예열한다. 예열은 기본으로 꺼져 있고, 지정한 인스턴스에서만 `MARKET_WARMUP_ENABLED=true`로 켠다. 켜진 인스턴스끼리도 리더 임대로 한 곳만 돈다. 옵션 외부 조회도 공유 예산 1회를 사용한다. 거래소 캐시 장애는 `503 MARKET_CACHE_UNAVAILABLE`이며 외부 API 우회나 오래된 응답 반환을 하지 않는다. [거래소 옵션·캐시 계약](market-cache.md)을 참고한다.

## 계측 계약

Actuator가 제공하는 MeterRegistry에 기록한다. 태그는 코드의 고정 enum 또는 success/failure만 사용한다. 캐릭터명·서버명·원문 URL·API 키·예외 메시지는 태그로 받지 않는다.

| Meter | 종류 | 태그 | 의미 |
|---|---|---|---|
| loakraken.lostark.requests | Timer | endpoint=armory/siblings/market_items/market_trades/market_options/market_item_history/game_calendar/news_notices/news_events/auction_items, outcome=success/not_found/rate_limited/timeout/error | 게이트 통과 후 HTTP 요청 시도부터 응답 수신·JSON 디코딩 종료까지 횟수와 지연 |
| loakraken.cache.operations | Counter | outcome=hit/miss/invalid/read_error/write_success/write_error | 캐시 읽기·쓰기 결과 및 손상 발견 |
| loakraken.lostark.gate | Counter | event=budget_reject/concurrency_reject/upstream_429/upstream_backoff_reject | 로컬 차단 사유와 외부 429 관측 |
| loakraken.character.searches | Counter | outcome=success/failure | 검색 application 서비스 호출의 완료 결과 |
| loakraken.market.cache.operations | Counter | kind=prices/options/item_history/board_scan, outcome=hit/miss/invalid/read_error/write_success/write_error | 거래소 캐시 연산 결과. 출처·검색 조건·해시·이름은 태그에 포함하지 않음 |
| loakraken.market.warmup | Counter | target=history/board, outcome=refreshed/not_found/failed/capped | 핵심 품목 이력(9분)·기본 보드(30초) 예열 결과([시세 보드](market-board.md)). 상품 ID는 태그에 포함하지 않음 |
| loakraken.character.views.writes | Counter | outcome=success/failure | 캐릭터 조회수 비동기 기록 결과(대기열 초과로 버린 조회 포함, [조회수](character-views.md)). 캐릭터명은 태그에 포함하지 않음 |
| loakraken.stats.collector | Counter | task=profile/roster/none, outcome=success/not_found/failed/yielded/storage_failed | 통계 표본 수집기 회차 결과([수집기](stats-collector.md)). 캐릭터명은 태그에 포함하지 않음 |
| loakraken.stats.collector.discovered | Counter | 없음 | 원정대로 새로 발견한 캐릭터 수 |
| loakraken.character.popular.writes | Counter | outcome=success/failure | 인기 검색 고유 조회 비동기 기록 결과([인기 검색](popular-characters.md)). IP·캐릭터명은 태그에 포함하지 않음 |
| loakraken.character.cards.writes | Counter | outcome=success/failure | 카드 기록·포지션·보석 평균 비동기 쓰기 결과([카드 기록](card-collection.md)). 대기열이 차서 버린 관측도 failure |
| loakraken.background.last_success | Gauge(초) | job=collector/warmup_history/warmup_board | 백그라운드 작업이 마지막으로 성공한 시각(epoch 초, #143). 0이면 이 인스턴스에서 아직 성공하지 않음. 작업이 켜진 인스턴스에만 생긴다 |
| http.server.requests | Timer(히스토그램) | method, uri(경로 템플릿), status, outcome | Spring 기본 HTTP 지표. `percentiles-histogram`을 켜 p95·p99를 Prometheus `histogram_quantile`로 계산한다(#143) |

통계 표본 수집기는 기본으로 꺼져 있다. 운영에서는 수집을 맡을 인스턴스 하나에만 `STATS_COLLECTOR_ENABLED=true`를 준다([수집기](stats-collector.md)).

외부 요청 Timer의 count가 요청 횟수이므로 별도 요청 Counter와 합산하지 않는다. success는 HTTP 200 및 타입 디코딩 성공이다. 이후 필수 프로필 검증이나 도메인 매핑에서 실패하면 HTTP 지표는 success여도 검색은 failure일 수 있다. not_found는 실제 외부 404이며 원정대 404를 공개 API에서 502로 바꾸는 정책과 구별된다. error에는 다른 HTTP 오류, 비어 있는 응답, JSON 디코딩 오류, 타임아웃 외 연결 오류가 포함된다. API 키 미설정과 로컬 게이트 거절은 HTTP 시도 지표를 만들지 않는다.

429 요청은 HTTP rate_limited와 게이트 upstream_429에 각각 하나씩 기록되며 서로 다른 관점이다. 이후 차단은 upstream_backoff_reject만 증가한다. 남은 할당량 0 헤더로 인한 차단도 같은 backoff 사유다. 차단 사유는 concurrency → backoff → budget 순서로 하나만 기록한다. 게이트 저장소(Valkey) 장애는 gate_unavailable로 기록한다.

캐시 지표는 사용자 검색 수가 아니라 저장소 연산 기준이다. 검색은 초기 읽기와 single flight 내부 재확인으로 miss가 두 번 발생할 수 있다. 손상 발견 invalid는 제거 성공 시 miss에도 기록되고, 제거 실패 시 read_error에도 기록된다. 적중률은 `hit / (hit + miss)`로 읽되 read_error를 별도로 확인한다. 서로 다른 결과를 전부 더해 검색 수로 사용하지 않는다.

검색 성공·실패는 캐시 적중과 동시 요청 병합 참여자를 포함해 각 서비스 호출당 한 번이다. 컨트롤러 입력 검증에서 종료된 요청은 여기에 포함되지 않으며 기본 HTTP 서버 지표로 확인한다.

거래소 캐시는 별도의 Counter를 사용하며 캐릭터 캐시·검색 Counter에 포함되지 않는다. 거래소 캐시도 초기 읽기와 병합 내부 재확인 때문에 miss가 둘 이상 기록될 수 있다. 손상 발견·시각 만료·문맥 불일치 invalid는 삭제 성공 시 miss, 삭제 실패 시 read_error와 함께 기록된다. 거래소 도메인 검증 실패는 HTTP Timer의 디코딩 성공과 별개이며 공개 HTTP 서버 응답 지표에서 502로 확인한다.

loakraken.* Meter 조합의 상한은 요청 25 + 캐릭터 캐시 6 + 게이트 4 + 캐릭터 검색 2 + 거래소 캐시 12 = 49개다. exporter가 생성하는 실제 시계열 수는 Timer의 count/total/max 등에 따라 더 많을 수 있다. 미발생 조합은 아직 등록되지 않을 수 있다. 기본 누적값은 프로세스 재시작 시 초기화된다.

## 확인 방법과 공개 범위

- 운영(`prod` 프로필)은 actuator를 **관리 포트**(`MANAGEMENT_SERVER_PORT`, 기본 9090)에서만 연다. 노출은 `health`·`prometheus`뿐이다. 9090은 internal·data망 안에서만 닿고 edge는 이 망에 없다(배포·운영 #131). 앱 포트(8080)에는 actuator가 없다.
- 수집: Prometheus가 `http://api:9090/actuator/prometheus`를 긁는다. 이름은 점이 밑줄로 바뀐다(예: `loakraken_background_last_success_seconds{job="collector"}`).
- 기본(로컬·테스트) 노출은 health뿐이고, 앱 포트의 `/actuator/metrics`·`/actuator/prometheus`는 404임을 통합 테스트로 확인한다. 관리 포트를 따로 연 구성은 `MonitoringIntegrationTest`가 확인한다.
- 지표 태그에는 요청 ID·IP·캐릭터명이 들어가지 않는다(통합 테스트로 확인). 관리 포트 9090은 internal망이라 web 컨테이너도 닿으므로, **지표에는 민감 정보를 넣지 않는다**는 원칙을 지킨다(새 지표를 만들 때 태그는 고정 enum만, #166 P2).

### 요청 상관 ID와 로그 필드

- 웹(web #159)이 `X-Request-Id`를 넘긴다. `[A-Za-z0-9._:-]{8,128}`이면 그대로 쓰고, 아니면 백엔드가 UUID를 만든다. 응답 헤더 `X-Request-Id`로 돌려준다(유입 제한 429 포함).
- 로그 MDC: `requestId`, `entryPoint`(`request`·`collector`·`warmup`). 운영은 ECS 구조화 로그(`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)라 MDC가 JSON 필드로 나간다.
- 클라이언트 IP 원문은 MDC·로그에 넣지 않는다. 유입 제한·인기 검색은 HMAC 키만 쓴다.

### 경보 제안(#143, 배포·운영 감시 설계 7절)

| 경보 | 식 |
|---|---|
| 수집기 멈춤 | `time() - loakraken_background_last_success_seconds{job="collector"} > 600`(켜진 인스턴스) |
| 보드 예열 멈춤 | `time() - loakraken_background_last_success_seconds{job="warmup_board"} > 180` |
| 응답 지연 | `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))) > 2` |
| 게이트 장애 | `increase(loakraken_lostark_gate_total{event="gate_unavailable"}[5m]) > 0` |

로컬에서 지표를 볼 때는 관리 포트를 loopback에 묶는다. 이 설정은 운영 공개 주소에 그대로 쓰지 않는다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local --management.server.port=9091 --management.server.address=127.0.0.1 --management.endpoints.web.exposure.include=health,metrics,prometheus'
```

Meter의 count/시간 의미는 [Micrometer Timer](https://docs.micrometer.io/micrometer/reference/concepts/timers.html), 노출 설정은 [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)를 기준으로 확인했다.
