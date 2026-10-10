# 거래소 상품 14일 시세 이력

`GET /api/v1/markets/items/{itemId}/history`는 공식 `GET /markets/items/{itemId}`의 최근 14일 일별 평균가와 거래량을 제공한다. 시세 메뉴의 차트·등락률(프론트 요청 B-5)과 이후 시세 환산 기능이 사용한다.

## 요청

- `itemId`: 공식 거래소 상품 ID. 1~999,999,999 정수만 받는다.
  - 상품 ID는 거래소 검색(`GET /api/v1/markets/items`) 응답의 `items[].id`에 있다.
- 잘못된 ID(0, 음수, 소수, 문자)는 외부 호출 없이 `400 INVALID_REQUEST`다.

## 응답

```json
{"itemId":66102006,"observedAt":"2026-10-05T16:37:30Z","baseDate":"2026-10-06",
 "variants":[{"name":"운명의 파괴석","tradeRemainCount":null,"bundleCount":100,
   "days":[{"date":"2026-10-06","averagePrice":175.4,"tradeCount":8893,"partial":true},
           {"date":"2026-10-05","averagePrice":172.6,"tradeCount":247145,"partial":false}],
   "summary":{"latestDate":"2026-10-05","latestAveragePrice":172.6,"previousAveragePrice":165.4,
     "dayChangeRate":4.35,"sevenDayDays":7,"sevenDayAveragePrice":165.65,"sevenDayChangeRate":4.2,
     "sevenDayTradeCount":1713373}}]}
```

- `variants`: 공식 응답 배열 그대로다. 같은 ID라도 거래 가능 횟수·묶음이 다른 변형이 여러 개 올 수 있다.
- `days`: 최신순이다.
  - 공식 Stats는 오늘을 포함해 14개다.
  - `averagePrice`는 묶음 기준 공식 평균 거래가다. 1개당 가격은 `bundleCount`로 나눈다.
- `baseDate`: `observedAt`의 한국 날짜다. 이 날짜 이후의 값은 진행 중인 부분 집계라 `partial=true`이고, 요약에서 뺀다.
- `summary`: 완료일만 쓴다. 등락률은 % 단위이며 소수 둘째 자리까지다.
  - `latestDate`, `latestAveragePrice`: 가장 최근 완료일
  - `previousAveragePrice`, `dayChangeRate`: 그 전 완료일과 비교한 값. 하나뿐이면 null
  - `sevenDayAveragePrice`: 최근 완료일 최대 7일의 거래량 가중 평균. 거래량 합이 0이면 null. 실제로 쓴 일수는 `sevenDayDays`, 거래량 합은 `sevenDayTradeCount`
  - `sevenDayChangeRate`: 7일 가중 평균 대비 최근 완료일 등락률

## 호출·캐시·오류

- Valkey `market:v1:item-history:{itemId}` 키로 캐시한다. 기본 10분이고 `market.cache.history-ttl`로 바꾼다.
  - 같은 상품의 동시 요청은 한 번만 조회한다.
  - 캐릭터·거래소가 공유하는 90회/분·동시 4개 예산을 쓴다.
- 공식 API는 모르는 ID에 200과 본문 `null`을 돌려준다. 이 경우 `404 MARKET_ITEM_NOT_FOUND`로 응답하고 캐시하지 않는다.
- 기존 429/502/503/504는 그대로 전달한다.

## 검증

- 2026-10-06 실서버
  - 운명의 파괴석(66102006): 14일, 오늘 partial, 전일 대비 +4.35%
  - 운명의 파편 주머니(소)(66130141): 전일 대비 −2.59%
  - 없는 ID 1: 404
  - ID 0: 400
- 테스트
  - 도메인: 부분 집계 제외, 가중 평균, 비교 값 null
  - 클라이언트: GET 경로, 변형 보존, null → 없음 지표
  - HTTP: 캐시 재사용, 404 미캐시, 429, 400
  - OpenAPI: 계약 확인. 합성 산출물은 `market-item-history.json`이다.
