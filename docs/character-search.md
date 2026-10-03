# 캐릭터 검색

`GET /api/v1/characters/search?characterName=모험가`

이 단계에서는 프로필만 제공합니다. 장비·보석·각인 집계와 스냅샷 저장은 후속 작업입니다.
이름은 앞뒤 공백 제거와 Unicode NFC 정규화 후 조회하며 문자·숫자 1~64자를 허용합니다.
성공 응답은 공통 봉투 안에 캐릭터명, 서버, 직업, 레벨, 아이템 레벨, 전투력,
원정대 레벨, 이미지, 칭호, 길드, 능력치와 수집 시각 `fetchedAt`을 담습니다.
공식 API의 쉼표 포함 숫자는 숫자형으로 변환하고 Tooltip과 외부 응답 전문은 노출하지 않습니다.

## 조회와 캐시

- Valkey 키: `character:v1:{정규화한 캐릭터명}:profile`, 기본 TTL 5분
- 캐시 적중 시 최초 수집 시각을 유지하고 외부 API를 호출하지 않습니다.
- 같은 이름의 동시 요청은 단일 애플리케이션 인스턴스 안에서 한 번으로 합칩니다.
- 손상된 캐시는 삭제 후 조회하며 캐시 연결 장애 시 503으로 종료합니다.
- 기본 연결 제한 3초, 읽기 제한 5초, 분당 최대 90회, 동시 외부 호출 최대 4회입니다.
- 공식 API의 잔여 요청량과 초기화 시각을 반영하고 자동 재시도하지 않습니다.
- 호출 제한과 동시 요청 병합은 인스턴스 단위입니다. 여러 인스턴스 운영 전 공유 제한이 필요합니다.

설정은 `lostark` 속성에서 변경합니다. API 키는 서버 환경 변수 `LOSTARK_API_KEY`로 전달합니다.

## 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | 이름 누락 또는 형식 오류 |
| 404 | CHARACTER_NOT_FOUND | 공식 API 404 또는 빈 프로필 |
| 429 | LOST_ARK_RATE_LIMITED | 호출량·동시 호출 제한 또는 공식 API 429 |
| 502 | LOST_ARK_API_ERROR | 공식 API 오류, 잘못된 응답, 연결 실패 |
| 503 | LOST_ARK_NOT_CONFIGURED | 서버 API 키 미설정 |
| 503 | CHARACTER_CACHE_UNAVAILABLE | 캐시 읽기·쓰기 실패 |
| 504 | LOST_ARK_API_TIMEOUT | 공식 API 응답 시간 초과 |

외부 오류 본문과 인증 정보는 응답에 포함하지 않습니다.
테스트는 로컬 HTTP 모의 서버와 실제 Valkey Testcontainers를 사용합니다.
공식 서비스에 대한 실제 키 호출은 별도 확인이 필요합니다.

공식 계약: [Armories Swagger](https://developer-lostark.game.onstove.com/swagger-doc/endpoints/armories),
[사용 가이드](https://developer-lostark.game.onstove.com/usage-guide).
