# 캐릭터 검색

`GET /api/v1/characters/search?characterName=모험가`

프로필과 장비·보석·각인·카드·아크패시브·아크그리드, 원정대 캐릭터를 한 번에 제공합니다.
이름은 앞뒤 공백 제거와 Unicode NFC 정규화 후 조회하며 문자·숫자 1~64자를 허용합니다.
성공 응답은 공통 봉투 안에 캐릭터명, 서버, 직업, 레벨, 아이템 레벨, 전투력,
원정대 레벨, 이미지, 칭호, 길드, 능력치, 전투정보 `loadout`, 원정대 `siblings`와 수집 완료 시각 `fetchedAt`을 담습니다.
공식 API의 쉼표 포함 숫자는 숫자형으로 변환하고 Tooltip과 외부 응답 전문은 노출하지 않습니다.

## 조회와 캐시

- Valkey 키: `character:v2:{정규화한 캐릭터명}:summary`, 기본 TTL 5분 (`lostark.summary-ttl`)
- 전투정보와 원정대 조회가 모두 성공한 결과만 캐시에 저장합니다. 두 요청은 순서대로 실행하며 각각 호출 예산을 사용합니다.
- 실제 수집 결과는 [PostgreSQL 스냅샷](character-snapshots.md)에 저장한 뒤 캐시에 반영합니다.
- 캐시 적중 시 최초 수집 시각을 유지하고 외부 API를 호출하지 않습니다.
- 같은 이름의 동시 요청은 단일 애플리케이션 인스턴스 안에서 한 번으로 합칩니다.
- 손상된 캐시는 삭제 후 조회하며 캐시 연결 장애 시 503으로 종료합니다.
- 기본 연결 제한 3초, 읽기 제한 5초, 분당 최대 90회, 동시 외부 호출 최대 4회입니다.
- 공식 API의 잔여 요청량과 초기화 시각을 반영하고 자동 재시도하지 않습니다.
- 호출 제한과 동시 요청 병합은 인스턴스 단위입니다. 여러 인스턴스 운영 전 공유 제한이 필요합니다.

설정은 `lostark` 속성에서 변경합니다. API 키는 서버 환경 변수 `LOSTARK_API_KEY`로 전달합니다.

## 전투정보

`loadout`은 `equipment`, `engravings`, `gems`, `cards`, `arkPassive`, `arkGrid`를 포함합니다.
목록 데이터가 없으면 빈 배열, 개별 섹션 데이터가 제공되지 않으면 `null`입니다.
`null`을 장비 미착용이나 기능 비활성화로 단정하지 않습니다. 예를 들어 `arkPassive.enabled=false`와
`arkPassive=null`은 서로 다른 상태입니다. 문자열은 외부 서비스의 이름·설명을 보존하므로 화면에서는
텍스트로 표시해야 하며 HTML로 삽입하지 않습니다.

장비 품질·재련 수치처럼 Tooltip 해석이 필요한 정보와 성능 점수는 아직 제공하지 않습니다.
캐시 키 버전을 분리했으므로 기존 프로필 캐시가 종합 응답으로 사용되지 않습니다.
원정대 목록은 공식 Siblings 응답 전체이며 서버로 임의 필터링하지 않습니다.
`analysis`는 [기본 분석](character-analysis.md)의 관측값 집계를 제공합니다.

## 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | 이름 누락 또는 형식 오류 |
| 404 | CHARACTER_NOT_FOUND | 공식 API 404 또는 빈 프로필 |
| 429 | LOST_ARK_RATE_LIMITED | 호출량·동시 호출 제한 또는 공식 API 429 |
| 502 | LOST_ARK_API_ERROR | 공식 API 오류, 잘못된 응답, 연결 실패 |
| 503 | LOST_ARK_NOT_CONFIGURED | 서버 API 키 미설정 |
| 503 | CHARACTER_CACHE_UNAVAILABLE | 캐시 읽기·쓰기 실패 |
| 503 | CHARACTER_STORAGE_UNAVAILABLE | 수집 결과 DB 저장 실패 |
| 504 | LOST_ARK_API_TIMEOUT | 공식 API 응답 시간 초과 |

외부 오류 본문과 인증 정보는 응답에 포함하지 않습니다.
테스트는 로컬 HTTP 모의 서버와 실제 Valkey Testcontainers를 사용합니다.
공식 서비스에 대한 실제 키 호출은 별도 확인이 필요합니다.

공식 계약: [Armories Swagger](https://developer-lostark.game.onstove.com/swagger-doc/endpoints/armories),
[사용 가이드](https://developer-lostark.game.onstove.com/usage-guide).
