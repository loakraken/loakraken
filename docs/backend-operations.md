# 백엔드 MVP 운영과 지표

## 실행 전제

MVP는 **동일 API 키를 사용하는 백엔드 프로세스 1개, replicas=1**을 전제로 한다. 배포 인프라를 새로 만들거나 replica 수를 자동 강제하는 코드를 추가한 것은 아니다. 현재 single flight, 호출 예산, 동시성 제한은 JVM 내부 상태다.

기본 외부 호출 예산은 인스턴스당 90회/분, 동시 요청 4개이며 공식 100회/분 한도에 여유를 둔다. 검색 캐시 미적중은 전투정보·원정대 요청 2회를 사용할 수 있다. 배치·로컬 테스트·다른 서비스가 같은 키를 사용하면 총량을 공유한다. 단일 replica만으로 다른 프로세스까지 제어되지는 않는다.

운영 시 동일 키를 쓰는 프로세스를 함께 관리하고, 배포 중 구·신 인스턴스를 겹쳐 실행하지 않는다. 재시작은 JVM 예산 기록을 초기화하므로 필요 시 기존 요청 구간이 끝날 때까지 기다린다. 여러 인스턴스가 필요하면 배포 전에 공유 quota와 갱신 조정을 설계한다. 현재 문서는 분산 제한을 구현했다는 의미가 아니다.

Valkey 읽기·쓰기 장애는 `503 CHARACTER_CACHE_UNAVAILABLE`로 종료한다. 캐시 장애를 미적중처럼 취급하거나 외부 API로 자동 우회하지 않는다. 손상된 JSON은 제거한 뒤 재조회하지만 삭제 자체가 실패하면 503이다. 외부 API는 자동 재시도하지 않는다.

관측 저장·이력 공개는 `CHARACTER_SNAPSHOTS_ENABLED=false`를 유지한다. 저장 범위 확인 전 diff·장기 통계·성장 그래프를 확장하지 않는다.

## 계측 계약

Actuator가 제공하는 MeterRegistry에 기록한다. 태그는 코드의 고정 enum 또는 success/failure만 사용한다. 캐릭터명·서버명·원문 URL·API 키·예외 메시지는 태그로 받지 않는다.

| Meter | 종류 | 태그 | 의미 |
|---|---|---|---|
| loakraken.lostark.requests | Timer | endpoint=armory/siblings, outcome=success/not_found/rate_limited/timeout/error | 게이트 통과 후 HTTP 요청 시도부터 응답 수신·JSON 디코딩 종료까지 횟수와 지연 |
| loakraken.cache.operations | Counter | outcome=hit/miss/invalid/read_error/write_success/write_error | 캐시 읽기·쓰기 결과 및 손상 발견 |
| loakraken.lostark.gate | Counter | event=budget_reject/concurrency_reject/upstream_429/upstream_backoff_reject | 로컬 차단 사유와 외부 429 관측 |
| loakraken.character.searches | Counter | outcome=success/failure | 검색 application 서비스 호출의 완료 결과 |

외부 요청 Timer의 count가 요청 횟수이므로 별도 요청 Counter와 합산하지 않는다. success는 HTTP 200 및 타입 디코딩 성공이다. 이후 필수 프로필 검증이나 도메인 매핑에서 실패하면 HTTP 지표는 success여도 검색은 failure일 수 있다. not_found는 실제 외부 404이며 원정대 404를 공개 API에서 502로 바꾸는 정책과 구별된다. error에는 다른 HTTP 오류, 비어 있는 응답, JSON 디코딩 오류, 타임아웃 외 연결 오류가 포함된다. API 키 미설정과 로컬 게이트 거절은 HTTP 시도 지표를 만들지 않는다.

429 요청은 HTTP rate_limited와 게이트 upstream_429에 각각 하나씩 기록되며 서로 다른 관점이다. 이후 차단은 upstream_backoff_reject만 증가한다. 남은 할당량 0 헤더로 인한 차단도 같은 backoff 사유다. 차단 사유는 backoff → budget → concurrency 순서로 하나만 기록한다.

캐시 지표는 사용자 검색 수가 아니라 저장소 연산 기준이다. 검색은 초기 읽기와 single flight 내부 재확인으로 miss가 두 번 발생할 수 있다. 손상 발견 invalid는 제거 성공 시 miss에도 기록되고, 제거 실패 시 read_error에도 기록된다. 적중률은 `hit / (hit + miss)`로 읽되 read_error를 별도로 확인한다. 서로 다른 결과를 전부 더해 검색 수로 사용하지 않는다.

검색 성공·실패는 캐시 적중과 동시 요청 병합 참여자를 포함해 각 서비스 호출당 한 번이다. 컨트롤러 입력 검증에서 종료된 요청은 여기에 포함되지 않으며 기본 HTTP 서버 지표로 확인한다.

이번에 추가한 loakraken.* Meter 조합의 상한은 요청 10 + 캐시 6 + 게이트 4 + 검색 2 = 22개다. exporter가 생성하는 실제 시계열 수는 Timer의 count/total/max 등에 따라 더 많을 수 있다. 미발생 조합은 아직 등록되지 않을 수 있다. 기본 누적값은 프로세스 재시작 시 초기화된다.

## 확인 방법과 공개 범위

테스트는 SimpleMeterRegistry에서 각 결과와 횟수·지연을 확인한다. Prometheus registry와 외부 모니터링 서버는 아직 연결하지 않았다. 기본 HTTP 노출은 health이며 `/actuator/metrics`와 `/actuator/prometheus`는 404임을 통합 테스트로 확인한다.

로컬에서 지표를 확인해야 한다면 별도 관리 포트를 loopback에 묶어 실행할 수 있다. 이 설정은 운영의 공개 주소에 그대로 적용하지 않는다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local --management.server.port=9091 --management.server.address=127.0.0.1 --management.endpoints.web.exposure.include=health,metrics'
```

로컬 요청을 처리한 뒤 `http://127.0.0.1:9091/actuator/metrics/loakraken.character.searches` 등으로 확인한다. 이 문서의 명령을 자동 실행하거나 실제 캐릭터 API를 추가 호출하지는 않았다. 운영 수집 경로는 인증·네트워크 접근 범위를 정한 뒤 별도로 구성한다.

Meter의 count/시간 의미는 [Micrometer Timer](https://docs.micrometer.io/micrometer/reference/concepts/timers.html), 노출 설정은 [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)를 기준으로 확인했다.
