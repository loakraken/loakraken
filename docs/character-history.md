# 저장된 캐릭터 관측 이력 API

`GET /api/v1/characters/snapshots`는 DB에 이미 저장된 스냅샷의 프로필 요약을 반환한다. API 키와 Valkey 조회를 사용하지 않으며 Lost Ark API를 호출하거나 새 스냅샷을 만들지 않는다.

## 요청

| 파라미터 | 형식 | 의미 |
|---|---|---|
| characterName | 필수 문자열, 문자/숫자 1–64자 | 검색 응답에서 받은 캐릭터명 |
| serverName | 필수 문자열, 문자/숫자 1–64자 | 검색 응답에서 받은 서버명 |
| limit | 1–50 정수, 기본 20 | 한 페이지 최대 개수 |
| cursor | 선택 UUID | 직전 응답의 nextCursor. 첫 조회는 생략 |

캐릭터명과 서버명은 앞뒤 공백 제거 후 NFC로 정규화한다. 서버·이름이 함께 일치하는 기록만 반환한다. 이름 변경이나 서버 이동을 자동 연결하지 않는다.

```http
GET /api/v1/characters/snapshots?characterName=모험가&serverName=루페온&limit=20
Accept: application/json
```

실제 URL을 만들 때 각 쿼리 값은 URL 인코딩한다. 응답의 nextCursor가 null이면 다음 요청을 보내지 않는다. 계속 조회할 때 characterName과 serverName은 그대로 유지한다.

## 응답

```json
{
  "code": "CHARACTER_HISTORY_SUCCESS",
  "message": "캐릭터 관측 이력 조회에 성공했습니다.",
  "data": {
    "items": [{
      "snapshotId": "5f0fd436-b5be-4fb7-9d80-b9e8b00bfda0",
      "fetchedAt": "2026-10-04T00:00:00Z",
      "className": "바드",
      "itemLevel": 1700.50,
      "combatPower": null
    }],
    "nextCursor": null
  }
}
```

예시는 계약 설명용 가상 데이터다. 모든 응답 키를 항상 포함하고, 제공되지 않은 전투력은 null이다. 당시 원본 ArmoryProfile의 숫자를 기존 검색과 같은 방식으로 정규화한다. 현재 characters 행의 최신 수치를 과거 기록에 사용하지 않는다. 원본 JSON, Tooltip, 미해석 필드는 공개하지 않는다.

## 정렬과 커서

- 수집 시각 내림차순, 같은 시각은 저장 순서 내림차순이다.
- 서버와 캐릭터명이 일치하는 커서 기록의 시각·저장 순서보다 앞선 기록을 읽는다. UUID 자체를 시간순으로 비교하지 않는다.
- 최대 limit + 1개를 읽어 다음 페이지 유무를 판단하며 전체 개수 COUNT와 OFFSET은 사용하지 않는다.
- 조회 도중 더 최신 기록이 저장되어도 다음 페이지에 중복되지 않는다. 새 기록을 보려면 첫 페이지를 다시 조회한다.
- 공개 snapshotId는 이력 식별자다. 내부 순번이나 저장 스키마 버전은 노출하지 않는다.
- 읽기 전용 트랜잭션의 제한 시간은 3초다. 현재 보존 정책상 기록을 자동 삭제하지 않으며, 향후 삭제 정책이 도입되면 삭제된 커서는 400으로 처리된다.

## 상태 코드

| HTTP | code | 의미 |
|---|---|---|
| 200 | CHARACTER_HISTORY_SUCCESS | 조회 성공. 미등록 캐릭터/서버 또는 기록 없음도 items: [], nextCursor: null |
| 400 | INVALID_REQUEST | 필수 입력 누락, 형식 또는 limit 범위 오류 |
| 400 | INVALID_SNAPSHOT_CURSOR | 존재하지 않거나 다른 캐릭터/서버에 속한 커서 |
| 406 | NOT_ACCEPTABLE | JSON 이외의 응답 형식 요청 |
| 503 | CHARACTER_HISTORY_UNAVAILABLE | DB 장애, 처리할 수 없는 원본/저장 버전. 불완전한 목록을 성공으로 반환하지 않음 |
| 500 | INTERNAL_SERVER_ERROR | 예상하지 못한 내부 오류 |

이력 없음은 게임 캐릭터 미존재라는 뜻이 아니므로 404로 표현하지 않는다. 이 API는 외부 API를 호출하지 않으므로 검색 API의 429/502/504를 그대로 나열하지 않는다.

## 해석 시 주의할 계약

- fetchedAt은 이 스냅샷을 관측한 시각이며 강화 또는 세팅 변경이 발생한 실제 시각이 아니다.
- 같은 원본이 다시 조회되면 characters.last_synced_at만 갱신되고 스냅샷은 추가되지 않는다. 따라서 이력의 마지막 fetchedAt은 검색 응답의 fetchedAt보다 오래될 수 있다.
- 장비나 원정대 변경만으로도 원본 스냅샷이 생긴다. 같은 아이템 레벨/전투력인 항목이 여러 개여도 중복 오류가 아니다.
- 전투력 감소나 아이템 레벨 감소는 다른 세팅으로 조회한 결과일 수 있다. 성장 실패나 성능 저하를 자동 판정하지 않는다.
- 이 API는 모든 시점의 게임 이력이나 검증된 성능 변화량을 보장하지 않는다. 현 버전은 프로필 수치 요약만 제공한다.

## 검증과 연동

실제 PostgreSQL 통합 테스트로 동일 시각 정렬, 중간 삽입 후 다음 페이지, 같은 원본 재조회, 서버 분리, 기본/최대 개수, 잘못된 커서, 손상 데이터와 저장 버전을 검증한다. 테스트 전체에서 외부 조회와 캐시 호출이 없음을 확인한다.

CI에서 Java 통합 테스트를 실행하고 openapi-contract 산출물에 OpenAPI 명세와 정상·빈 목록·잘못된 커서·저장소 오류 응답을 함께 보관한다. 전체 JSON Schema 대조는 로컬 선택 검사다. 프론트엔드는 별도 채팅에서 이 계약을 사용하며 이 PR에는 프론트 코드 변경이 없다.

저장 정책은 [스냅샷 저장](character-snapshots.md), 현재 상태의 분석은 [관측 기반 진단](character-diagnostics.md)을 참고한다.
