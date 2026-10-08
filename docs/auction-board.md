# 경매장 보석·장신구 시세 표 (auction-board-v1)

일로아 시세의 보석·장신구 탭, 로아와·로아랩·로아베스팅의 보석 시세를 흡수한다(PM 배정 B). 공식 `POST /auctions/items`로 만든다.

## 표

| 경로 | 칸 | 공식 호출 |
|---|---|---|
| `GET /api/v1/markets/auctions/gems` | 겁화·작열 × 10→1레벨(20칸) | 칸당 1회 |
| `GET /api/v1/markets/auctions/accessories?grade=ANCIENT\|RELIC&role=DEALER\|SUPPORT&minQuality=70` | 목걸이·귀걸이·반지 × 연마 조합 | 딜러 24칸, 서포터 19칸 |

- 칸 값
  - minBuyPrice: 즉시 구매 최저가
  - totalCount: 공식 TotalCount
  - previousDayChangeRate: 전날 마지막 관측 최저가 대비 %
  - observedAt, status(READY·PENDING)
- 장신구 핵심 연마 효과(일로아와 같음)

| 부위 | 딜러 | 서포터 |
|---|---|---|
| 목걸이 | 적주피·추피 | 낙인력·아덴(세레나데·신앙·조화 게이지) |
| 귀걸이 | 무공%·공% | 무공%(단일, 상·중·하) |
| 반지 | 치피·치적 | 아피강·아공강 |

- 조합 순서: 상상·상중·중상·중중·상하·하상·중하·하중(하하 제외)
- 품질: 기본 70 이상(공식 10 단위), 0이면 무관
- T4 하·중·상 값

| 효과 | 하 | 중 | 상 |
|---|---|---|---|
| 적주피 | 0.55 | 1.20 | 2.00 |
| 추피 | 0.70 | 1.60 | 2.60 |
| 무공% | 0.80 | 1.80 | 3.00 |
| 공%·치적 | 0.40 | 0.95 | 1.55 |
| 치피 | 1.10 | 2.40 | 4.00 |
| 낙인력 | 2.15 | 4.80 | 8.00 |
| 아덴 | 1.60 | 3.60 | 6.00 |
| 아공강 | 1.35 | 3.00 | 5.00 |
| 아피강 | 2.00 | 4.50 | 7.50 |

출처: 공식 `/auctions/options`의 연마 효과(EtcOptions 7) 값 목록에서 T4 값이다. 검색 조건은 값과 정확히 같게 건다(×100 정수, Min=Max).

## 호출 예산

- 칸마다 Valkey `market:v1:auction-cell:{조건 해시}`에 3분 캐시한다(PM 결정: 나머지 3분 캐시).
- 비어 있는 칸만, 게이트에 사용자 몫이 남을 때(`backgroundAvailable(40)`) 채운다.
  - 한 요청에 최대 24칸을 채우고, 동시에 한 요청만 채운다(tryLock).
  - 채우지 못한 칸은 `PENDING`이고 `complete=false`다. 프론트는 잠시 뒤 다시 조회한다.
- 매물(`/gems/listings`, `/accessories/listings`)은 1회 검색이다. 1분 캐시하며 사용자 요청과 같은 게이트를 쓴다.
- 공유 게이트(#107)가 들어오면 `backgroundAvailable`이 인스턴스 합산 기준이 된다.

## 전일 대비

공식 API에 경매장 이력이 없다. 그래서 칸을 관측할 때마다 KST 날짜별 마지막 최저가를 `auction_price_daily`(V9)에 남기고, 전날 값과 비교한다. 전날 관측이 없으면 null이다. 추이 그래프(7일·30일)는 이 기록이 쌓인 뒤 후속으로 연다.

## 매물

- `GET /api/v1/markets/auctions/gems/listings?kind=겁화|작열&level=1~10&page=`
- `GET /api/v1/markets/auctions/accessories/listings?grade=&role=&slot=NECKLACE|EARRING|RING&levels=HIGH,MID&minQuality=&page=`
- 항목: name, grade, quality, buyPrice, bidPrice, bidStartPrice, endDate, tradeAllowCount, options[]
- 옵션의 level: 공식 Type이 `ACCESSORY_UPGRADE`인 T4 연마 효과면 LOW·MID·HIGH, 아니면 null이다. 보석은 className(대상 직업)을 준다.

## 검증 (2026-10-09 01:10 KST, 로컬 8081)

- 보석 20칸: 5.5초에 채웠다. 일로아(01:09 기준)와 17칸이 같았다. 나머지 3칸은 1분 안의 시세 변동이다(1G~59G).
- 장신구 딜러 고대 24칸과 서포터 고대 19칸: 같은 시점의 일로아와 **모두 일치**했다.
- 실조회 공식 호출: 보석 20 + 딜러 24 + 서포터 19 + 매물 1 + 사전 확인 4 = 68회
