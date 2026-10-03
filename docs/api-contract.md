# API 공통 응답

성공과 오류 모두 `code`, `message`, `data` 필드를 사용합니다.
HTTP 상태와 업무 코드는 서로 다른 역할을 하며 오류를 HTTP 200으로 감싸지 않습니다.

```json
{
  "code": "CHARACTER_SEARCH_SUCCESS",
  "message": "캐릭터 조회에 성공했습니다.",
  "data": {}
}
```

오류 응답의 `data`는 `null`입니다. 내부 예외 메시지, 외부 응답 전문, 입력 원문은 포함하지 않습니다.

| HTTP 상태 | 공통 코드 | 의미 |
|---|---|---|
| 400 | INVALID_REQUEST | 누락·형식·Validation 오류 |
| 404 | RESOURCE_NOT_FOUND | 없는 경로 |
| 405 | METHOD_NOT_ALLOWED | 허용되지 않는 메서드, Allow 헤더 유지 |
| 406 | NOT_ACCEPTABLE | 지원하지 않는 응답 형식 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 지원하지 않는 요청 본문 형식 |
| 500 | INTERNAL_SERVER_ERROR | 예상하지 못한 서버 오류 |

이외의 Spring MVC 오류는 원래 HTTP 상태·헤더를 유지하고 `HTTP_{상태번호}`로 응답합니다.
도메인 예외는 해당 도메인의 ErrorCode와 메시지를 사용합니다.
도메인 SuccessCode/ErrorCode의 메시지는 외부 응답이나 사용자 입력을 합성하지 않은 공개용 문구입니다.

모듈 간 공유 계약은 `common::api` Named Interface로 노출합니다.
전역 예외 처리 구현은 common 모듈 내부에 두며 다른 모듈에서 직접 의존하지 않습니다.
