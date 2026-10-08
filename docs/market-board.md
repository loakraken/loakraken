# 거래소 시세 보드 (market-board-v1)

시세 메뉴(프론트 명세 B-5)의 탭·정렬·등락률 목록이다. 설계 근거와 프론트 합의(Q1~Q4)는 볼트 `10 백엔드/04 설계/시세 보드 설계 (B-5).md`에 있다. 품목 상세는 [market-item-history.md](market-item-history.md)를 본다.

## 엔드포인트

- `GET /api/v1/markets/tabs`: 탭 정의와 정렬 값을 준다. 외부 호출은 없다.
- `GET /api/v1/markets/board?tab=&tier=&sort=&page=`
  - `tab`(필수): 아래 탭 표의 이름
  - `tier`: 1~4. 생략하면 탭의 기본 티어를 쓴다.
  - `sort`: `default`, `high`, `low`, `rise`, `fall`. 소문자이며 기본값은 `default`다.
  - `page`: 1~10. 한 페이지는 10개다.

| 탭 | 공식 코드 | 기본 티어 |
|---|---|---|
| REFINING 재련 재료 | 50010 | 4 |
| REFINING_EXTRA 재련 추가 재료 | 50020 | 4 |
| ARK_GRID 아크 그리드 재료 | 230000 | — |
| WEAPON_EVOLUTION 무기 진화 재료 | 51100 | — |
| ENGRAVING_BOOK 각인서 | 40000 | — |
| LIFE 생활 | 90000 | — |
| BATTLE_ITEM 전투 용품 | 60000 | — |
| FOOD 요리 | 70000 | — |

- 51000(기타 재료)은 2026-10-08 기준 품목이 0개라 제외했다.
- 보석·장신구는 경매장 API 대상이라 거래소 보드에 없다.

## 정렬과 호출

| sort | 공식 조회 | 캐시 |
|---|---|---|
| default | 등급 내림차순 해당 페이지 | 검색 캐시 30초 공유 |
| high / low | 현재 최저가 내림/오름차순 해당 페이지 | 검색 캐시 30초 공유 |
| rise / fall | 탭 전체를 1페이지부터 순차 스캔(최대 10페이지) 후 서버에서 등락률로 정렬하고 10개씩 자름 | 탭+티어 스캔 3분(`market.cache.board-scan-ttl`), 동시 요청 병합 |

- 상승·하락은 같은 스캔을 공유한다. 3분 동안 페이지를 넘겨도 같은 스냅샷(`observedAt` 동일) 안에서 자르므로 순서가 흔들리지 않는다.
- 스캔은 병렬로 돌리지 않는다. 서로 다른 탭·티어의 전체 스캔도 서버 전체에서 한 번에 하나만 실행한다. 캐릭터 검색과 공유하는 동시 4개·분당 90회 예산을 몰아 쓰지 않기 위해서다.
- 각인서(86개)도 9회 호출이면 된다.
- `scannedItems < totalItems`(10페이지 상한 초과)이면 `complete=false`다.
- 같은 품목이 페이지 경계에서 두 번 나오면 첫 번째만 남긴다.

## 등락률

- `changeDay` = 현재 최저가 ÷ 전일 평균가 − 1이다. %이고 소수 첫째 자리이며, 검색 결과만으로 계산한다(추가 호출 0).
  - 전일 평균가가 0이나 null이거나, 현재 최저가가 null이면 null이다. 전투 용품·요리에 많다.
  - 상승·하락 정렬에서 null은 맨 뒤로 가고, 같은 값은 이름·ID 순이다.
- `changeWeek` = 현재 최저가 ÷ 최근 완료일 최대 7일 거래량 가중 평균가 − 1이다.
- `spark7`: 최근 완료일 최대 7일의 평균가다. 오래된 날부터 나열한다.
- `changeWeek`와 `spark7`은 해당 품목의 이력 캐시(`market:v1:item-history:*`)가 살아 있을 때만 Valkey `MGET` 한 번으로 채운다.
  - 이력 캐시가 없으면 `changeWeek=null`, `spark7=[]`, `historyAvailable=false`다. 이 경우에도 외부 호출을 추가하지 않는다.
- 이력 변형은 묶음 수와 거래 가능 횟수가 같은 것을 우선 쓰고, 없으면 묶음 수만 같은 것을 쓴다.
- 가격은 모두 묶음 기준 골드다.

## 응답

`ruleVersion`, `changeBasis`, `tab`, `tabLabel`, `tier`, `sort`, `page`, `pageSize`, `observedAt`, `totalItems`, `scannedItems`, `complete`, `items[]{itemId, name, icon, grade, bundleCount, tradeRemainCount, currentMinPrice, recentPrice, yesterdayAvgPrice, changeDay, changeWeek, spark7[]{date, averagePrice}, historyAvailable}`

## 검증

- 2026-10-08 실서버
  - REFINING T4: 11개, 72ms
  - ENGRAVING_BOOK 상승: 86개 전체 스캔 217ms, 같은 스냅샷 9페이지는 8ms
  - FOOD 하락: 80개
  - 이력이 캐시된 운명의 파괴석: `changeWeek` 2.3%, `spark7` 7일
- 테스트
  - 단위: 등락률 반올림·null, 정렬 순서, 정렬별 공식 요청, 스캔 페이지 수·상한, 캐시 재사용, 7일 보강
  - HTTP: 스캔 재사용(외부 2회), 400 검증
  - OpenAPI: 계약 확인. 합성 산출물은 `market-board.json`, `market-tabs.json`이다.
