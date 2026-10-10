# 전투력 시뮬레이터 (combat-power-sim-v1)

로아랩(lo4.app) 전투력 시뮬레이터와 같은 기능이다(PM 총괄 방향 전환 10-10: 중복 기능은 로아랩 기준). 캐릭터의 현재 세팅에서 보석·재련·품질·연마·팔찌·각인·스톤·아크 패시브·아크 그리드 코어를 바꿨을 때 전투력이 얼마나 오르는지 보여 준다.

A단계는 **계약과 입력 검증만** 연다. 전투력 계산식은 계산 검증 팀이 목표 사이트 실측 대조로 항목별로 확정한 뒤(B단계부터) 채운다. 확정 전 항목은 값 없이 `UNSUPPORTED`로 알린다(추정 금지, 계산 일치 원칙).

- 플래그 `combat-power-sim.enabled`(환경 변수 `COMBAT_POWER_SIM_ENABLED`), 기본 false. 끄면 두 경로와 OpenAPI 계약에서 모두 빠진다.
- 운영은 보석·재련·각인 계산식이 계산 검증을 통과한 뒤에만 켠다(PM 총괄 10-10).
- **공식 API를 부르지 않는다.** 캐릭터 캐시(검색 API가 채움)만 읽는다. 요청 버킷은 GENERAL이다.

## `GET /api/v1/characters/combat-power/options`

화면 입력의 허용 범위다. 고정 값이라 캐릭터·`fetchedAt`이 없다.

- `verified=false`면 숫자 범위가 공식 근거로 확정되기 전 예시다. B단계에서 공식 표로 채우고 true로 바꾼다.
- 표본: `build/generated/combat-power-options.json`(통합 테스트가 만듦).

## `POST /api/v1/characters/combat-power/simulate`

```json
{"characterName":"모험가","changes":[
  {"kind":"GEM_LEVEL","slot":0,"level":10},
  {"kind":"EQUIPMENT_REFINE","index":0,"refine":22}
]}
```

`changes`는 최대 64개이고 빈 목록도 된다. 같은 대상을 두 번 바꾸면 뒤의 변경이 이기고, 자리는 처음 나온 곳을 지킨다.

| kind | 대상 | 바꿀 값 | 범위(예시) |
|---|---|---|---|
| `GEM_LEVEL_ALL` | 보석 전체 | `level` | 1–10 |
| `GEM_LEVEL` | `slot`(검색 응답 gems.items[].slot) | `level` | 1–10 |
| `EQUIPMENT_REFINE` | `index`(검색 응답 loadout.equipment 순번, 무기·방어구) | `refine` | 10–25 |
| `EQUIPMENT_ADVANCED_REFINE` | `index`(무기·방어구) | `level` | 0–40 |
| `EQUIPMENT_QUALITY` | `index`(품질이 있는 장비) | `quality` | 0–100 |
| `POLISH` | `index`, `line`(연마 효과 줄, 0부터) | `option`, `grade`(LOW·MID·HIGH) | |
| `BRACELET_EFFECT` | `line`(팔찌 효과 줄, 0부터) | `name`, `grade`(LOW·MID·HIGH) | |
| `ENGRAVING_LEVEL` | `name`(아크 패시브 각인 이름) | `level` | 0–4 |
| `STONE_ENGRAVING` | `name`(스톤 각인 이름) | `level` | 0–4 |
| `ARK_PASSIVE_POINT` | `tree`(진화·깨달음·도약) | `points` | 0–999 |
| `ARK_GRID_CORE` | `slotIndex`(아크 그리드 slot index) | `grade`(영웅·전설·유물·고대), `point` | 0–20 |

## 응답

| 필드 | 의미 |
|---|---|
| `fetchedAt` | 현재 세팅을 읽은 캐릭터 캐시의 수집 시각. 웹은 검색 응답 `fetchedAt`과 다르면 다시 불러온다 |
| `before` | 공식 전투력(armory CombatPower). 없으면 null |
| `modelBefore` | 현재 세팅의 모델값(계산 검증·QA용, 화면 표시 안 함). 계산식 확정 전 null |
| `after` | 바꾼 항목 중 `OK` 항목을 함께 적용한 전투력 |
| `delta`, `deltaPercent` | `after - before`, `delta / before × 100`(%) |
| `partial` | `UNSUPPORTED` 항목이 있어 `after`가 일부 변경만 반영했으면 true |
| `contributions[]` | 바꾼 항목별 `kind`·`target`·`from`·`to`·`delta`·`status`(OK·UNSUPPORTED)·`basis` |
| `unsupportedCount` | `UNSUPPORTED` 항목 수 |

- **after 계산(B단계부터)**: `after = before × model(바꾼 세팅) / model(현재 세팅)`. 공식 전투력에 모델 비율을 곱해, 모델이 공식 값과 조금 달라도 증가율은 모델 기준으로 맞춘다(PM-백엔드 18:27).
- **contributions[].delta**: 바꾼 항목을 순서대로 하나씩 적용했을 때의 증가분이다. `OK` 항목 delta의 합은 `delta`와 같다. 순서를 바꾸면 항목별 delta는 달라질 수 있지만 합은 같다.
- 응답 `kind`는 요청 kind와 같은 문자열이고 응답에서는 enum으로 고정하지 않는다. 새 kind가 생겨도 웹은 모르는 kind를 이름 그대로 보여 준다.
- `basis`에는 다른 사이트 이름을 쓰지 않는다.
- **반올림은 서버에서 끝낸다**: 전투력 값은 소수 둘째 자리, `deltaPercent`는 소수 셋째 자리(HALF_UP).
- 표본: `build/generated/character-combat-power-simulate.json`(통합 테스트가 만듦).

## 오류

| 상태 | 코드 | 경우 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 범위 밖, 모르는 kind, 필수 값 없음. 메시지에 필드와 허용 범위가 있다 |
| 404 | `CHARACTER_NOT_CACHED` | 캐릭터 캐시 없음(검색 먼저). 공개 제외 캐릭터도 같은 404다 |
| 409 | `CHANGE_TARGET_NOT_FOUND` | 캐시가 바뀌어 바꿀 대상이 캐릭터에 없음. 웹은 다시 불러오기를 띄운다 |
| 503 | `CHARACTER_STORAGE_UNAVAILABLE` | 공개 제외 확인 실패 |

## 성능

캐시 1회와 공개 제외 1회만 읽는다. 목표는 p95 200ms 이하다(PM-프론트 18:31). 입력 디바운스 300ms면 사용자 1명이 분당 최대 200회라 GENERAL 버킷(분당 240회) 안이다.

## 보석 계산 (combat-power-sim-v1)

`GEM_LEVEL`·`GEM_LEVEL_ALL`은 `OK`로 계산한다. 근거는 리소스 `combat-power/gem-coefficients.json`(출처·확인 날짜 포함)과 볼트 「전투력 검증 준비」 7·8절이다.

- 식: `전투력 비 = Π(1 + 보석 배수) × (1 + 기본 공격력 % 합)`. `after = before × 비(바뀐 세팅) / 비(현재 세팅)`.
- 보석 배수(4티어 겁화·작열·광휘 공통, 칸마다 곱): 1~10레벨 1.28·1.92·…·7.04%(`0.64 × (레벨 + 1)`). 출처는 인벤 「딜러 전투력 로직 분석」(https://www.inven.co.kr/board/lostark/4821/106546).
- 기본 공격력 %는 공식 툴팁 세 곳에서만 모은다.
  - 보석 효과 `기본 공격력 n% 증가`: 6레벨 0.45, 7레벨 0.60, 8레벨 0.80, 9레벨 1.00, 10레벨 1.20.
  - 어빌리티 스톤 `[레벨 보너스] 기본 공격력 +n%`.
  - 완갑 `추가 효과`의 `기본 공격력 +n%`.
- 교차 검증: 목표 사이트(로아랩) 딜러 전투력 시뮬레이터에서 5명 30건(보석 빼기, 6~10레벨, 전체 바꾸기)을 대조했고 모두 ±0.01 안에서 맞는다(계산 검증 #244, `src/test/resources/verification/combat-power/loalab-gem-deltas-2026-10-10.json`). 남은 0.01은 공식 전투력이 소수 둘째 자리까지만 와서 생기는 반올림이다.
- 아래 경우는 추정하지 않고 `UNSUPPORTED`로 두고 이유를 `basis`에 쓴다.
  - 세 곳 밖에서 `기본 공격력 n%` 문구가 나올 때(장비·아크 패시브·아크 그리드·각인)
  - 보석의 공식 표기가 계수표와 다를 때
  - 3티어 보석(멸화·홍염)이 있을 때(배수를 표본으로 아직 확인하지 않음)
  - 1~5레벨로 바꿀 때(기본 공격력 % 근거 없음)
  - 공식 전투력이 없을 때
- 보석 빼기(0레벨)는 계수표에 있지만 API 입력은 아직 1~10만 받는다.

## 단계

- **A단계**(#240): 계약·검증·표본. 모든 항목 `UNSUPPORTED`.
- **B단계**: 계산 검증이 확정한 항목부터 `OK`로 바꾼다. 보석(완료) → 장비 재련·품질 → 각인·스톤 → 아크 패시브 → 아크 그리드 순. 각 항목은 목표 사이트 실측(기준 캐릭터 언새늘)과 맞아야 한다. 맞지 않는 차이가 설명되지 않으면 P0다.
- 상급 재련의 현재 단계(`from`)는 검색 응답 `advancedRefineLevel`(#234)이다.
