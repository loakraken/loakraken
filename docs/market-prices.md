# 거래소 가격 조회

## 공개 API

- `GET /api/v1/markets/items?categoryCode=50000`: 현재 판매 상품의 가격 요약, `source=ACTIVE_LISTING`
- `GET /api/v1/markets/trades?categoryCode=50000`: 최근 거래 상품의 가격 요약, `source=RECENT_TRADE`

성공은 `200 MARKET_SEARCH_SUCCESS`와 공통 `code/message/data` 응답이다. 캐릭터 API와 같은 서버 API 키를 사용한다. 사용자에게 API 키를 받지 않는다.

두 API 모두 개별 매물이나 개별 거래 체결 이력을 반환하지 않는다. 최근 거래 응답을 현재 구매 가능한 매물로 취급하지 않는다. 공식 검색의 한 페이지만 읽으며, 전체 페이지 자동 순회와 재시도는 없다.

## 검색 조건

| 필드 | 규칙 |
|---|---|
| categoryCode | 필수 양의 정수. 공식 카테고리 코드를 사용한다. |
| characterClass | 선택, 32자 이하 |
| itemTier | 선택, 양의 정수 |
| itemGrade | 선택, 16자 이하 |
| itemName | 선택, 64자 이하 |
| pageNo | 기본 1, 서비스 허용 범위 1~1000 |
| sort | GRADE / YDAY_AVG_PRICE / RECENT_PRICE / CURRENT_MIN_PRICE, 기본 RECENT_PRICE |
| sortCondition | ASC / DESC, 기본 ASC |

문자열은 앞뒤 공백 제거·NFC 정규화 후 검증하고, 생략한 문자열은 빈 문자열로 전달한다. 적용한 조건은 `data.search`에 반환한다. 길이와 페이지 상한은 LOAKraken의 입력 정책이며 공식 API의 전체 허용 범위를 뜻하지 않는다.

카테고리·등급·직업·티어의 최신 허용 목록은 공식 `GET /markets/options`를 기준으로 한다. 이 단계에는 옵션 조회 API와 로컬 허용 목록 캐시를 추가하지 않았다. 형식에 맞더라도 공식 서버가 거절한 검색 조건은 외부 응답 실패 `502 LOST_ARK_API_ERROR`로 처리한다. `CURRENT_MIN_PRICE`는 공식 공용 요청 enum에 포함돼 있으나 최근 거래 응답에는 해당 가격 필드가 없다. 정렬을 지정했다고 없는 가격이 생성되지는 않는다.

## 가격과 시각

`items`는 공식 `Id`, `Name`, `Grade`, `Icon`, `BundleCount`, `TradeRemainCount`, `YDayAvgPrice`, `RecentPrice`를 대응하는 camelCase 필드로 제공한다. 현재 판매 목록에서만 `CurrentMinPrice`를 사용한다. 최근 거래의 `currentMinPrice`는 항상 null이다.

- 가격은 BigDecimal로 읽고 그대로 반환한다. 전일 평균·최근 거래·현재 최저 가격을 하나의 임의 대표 가격으로 합치지 않는다.
- 미제공 가격은 null, 공식 응답의 0은 0이다. null을 무료 또는 거래 없음으로 해석하지 않는다.
- `bundleCount`를 보존하며 개당 가격으로 자동 환산하지 않는다. 재거래 가능 횟수가 없는 경우도 null이다.
- `observedAt`은 외부 응답의 검증 완료 후 서버가 기록한 UTC 시각이다. 원천의 가격 갱신 시각이나 거래 체결 시각이 아니다.
- 공식 목록 응답에는 집계 시작·종료 시각이 없다. `windowFrom`과 `windowTo`는 null이다. 전일 평균 가격의 정확한 집계 경계·시간대도 추정하지 않는다.
- `pageNo/pageSize/totalCount`는 공식 응답에서 읽는다. 정상 `items=[]`는 성공이다. 목록 자체가 null/누락되면 빈 결과로 바꾸지 않고 실패한다.

아이템 ID·이름·묶음 수량 또는 페이지 정보가 없거나 잘못되면 502다. 제공된 음수 가격·음수 재거래 가능 횟수도 거절한다. 선택적인 등급·아이콘·가격의 누락은 null로 보존한다.

## 호출 제한과 오류

캐릭터와 거래소는 `common::lostark`의 동일 `LostArkRequestGate` 빈을 사용한다. 기본 90회/분·동시 요청 4개는 두 기능의 **합산 한도**다. 같은 프로세스의 거래소 요청 1회는 외부 예산 1회를 사용한다. 캐릭터 캐시 미적중은 최대 2회다. 별도 프로세스까지 공유하는 분산 제한은 아니다.

거래소 데이터는 DB·Valkey에 저장하지 않는다. 매번 공식 API를 조회하므로 프론트의 자동 반복 조회나 전체 페이지 선조회는 피해야 한다. 가격 캐시·옵션 조회·중복 요청 병합은 별도 단계다. 이 기능은 관측 저장 기본 비활성 정책을 변경하지 않는다.

| HTTP | code | 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | 로컬 입력 검증 실패 |
| 429 | LOST_ARK_RATE_LIMITED | 공유 예산·동시 호출 제한·공식 429 또는 이후 대기 구간 |
| 502 | LOST_ARK_API_ERROR | 비정상 HTTP, JSON, 원천 필수 필드·수치 검증 실패 |
| 503 | LOST_ARK_NOT_CONFIGURED | API 키 미설정 |
| 504 | LOST_ARK_API_TIMEOUT | 외부 요청 타임아웃 |

외부 404도 검색의 빈 결과로 해석하지 않고 502로 반환한다. 공식 오류 본문·키·예외 메시지는 공개 응답이나 지표 태그에 포함하지 않는다. Timer 태그에는 `market_items`, `market_trades`만 추가한다. 자세한 지표 범위는 [운영 문서](backend-operations.md)를 참고한다.

## 근거와 검증 범위

2026-10-04 [공식 Markets Swagger](https://developer-lostark.game.onstove.com/swagger-doc/endpoints/markets)를 인증 없이 읽어 확인했다. 실제 경로는 복수형 `POST /markets/trades`이며 현재 판매 검색은 `POST /markets/items`다. `TradeMarketItem`에는 `CurrentMinPrice`가 없고 두 목록 모두 거래 시각 필드를 제공하지 않는다. 이 검증은 공개 명세 확인이며 실제 운영 데이터 검증과 구분한다.

로컬 HTTP 모의 서버에서 인증·POST 본문·가격 매핑·null과 0·잘못된 응답·429·타임아웃을 검증한다. Spring 통합 테스트는 캐릭터 2회+거래소 1회 뒤 공유 예산 차단, 요청 검증, 응답 직렬화, OpenAPI nullable 계약을 검증한다. 합성 응답은 `build/generated/market-items.json`, `market-trades.json`으로 생성하며 CI 산출물에 포함한다. 실제 Lost Ark 인증 API는 추가 호출하지 않는다.

다음 비용 분석에서는 가격 출처 선택, 묶음 단위, 수수료, 계산 가정, 규칙 버전을 먼저 정해야 한다. 현재 API에는 추천·효율·DPS 점수가 없다.
