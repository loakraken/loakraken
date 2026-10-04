# 관측 근거 기반 캐릭터 진단

`GET /api/v1/characters/search`의 `data.analysis.diagnostics`에 포함된다.
경로·기존 필드·캐시 저장 형식은 유지하고 응답 필드만 추가한다. API의 추가 필드를 엄격히 거부하는 클라이언트는 명세를 갱신해야 한다.

```json
{
  "ruleVersion": "observed-v2",
  "findings": [
    {
      "code": "ARK_GRID_GEM_INACTIVE",
      "category": "SETTING_CHECK",
      "evidencePath": "/data/analysis/arkGrid/inactiveGemCount",
      "observedCount": 2,
      "message": "비활성으로 조회된 아크그리드 젬이 있습니다. 의도한 세팅인지 확인해 주세요."
    }
  ]
}
```

예시는 계약 설명용이며 특정 캐릭터의 실제 결과가 아니다. `observedCount`는 개수 진단일 때만 숫자이고, 불리언이나 미제공 값을 근거로 할 때는 null이다. `evidencePath`는 `data`를 포함한 **전체 API 응답** 기준 JSON Pointer다. 카드/패시브 등 원본의 비정상 값은 기존 집계에서 미확인으로 분류한 결과를 그대로 사용한다.

## 분류와 규칙

| 코드 | 조건 / 근거 | 분류 |
|---|---|---|
| EQUIPMENT_UNAVAILABLE | coverage.equipment = NOT_PROVIDED. evidencePath는 /data/coverage/equipment, observedCount는 null | DATA_GAP |
| EQUIPMENT_NOT_OBSERVED | 제공된 장비 목록에서 equipmentCount = 0. 미착용을 확정하지 않음 | DATA_GAP |
| GEM_ITEMS_UNAVAILABLE / CARD_ITEMS_UNAVAILABLE | 섹션은 제공됐지만 coverage의 해당 collections.items = NOT_PROVIDED | DATA_GAP |
| ARK_GRID_SLOTS_UNAVAILABLE | 섹션은 제공됐지만 coverage.arkGrid.collections.slots = NOT_PROVIDED | DATA_GAP |
| GEMS_UNAVAILABLE / CARDS_UNAVAILABLE / ENGRAVINGS_UNAVAILABLE / ARK_PASSIVE_UNAVAILABLE / ARK_GRID_UNAVAILABLE | 해당 analysis 섹션 = null | DATA_GAP |
| GEMS_NOT_OBSERVED / CARDS_NOT_OBSERVED / ARK_GRID_NOT_OBSERVED | 제공된 섹션의 항목/코어 개수 = 0 | DATA_GAP |
| GEM_LEVEL_UNKNOWN | unknownLevelCount > 0. 평균/분포에서 제외됨 | DATA_GAP |
| CARD_AWAKENING_UNKNOWN | unknownAwakeningCount > 0. 알려진 각성 합계에서 제외됨 | DATA_GAP |
| ARK_PASSIVE_STATE_UNKNOWN | enabled = null | DATA_GAP |
| ARK_PASSIVE_POINT_UNKNOWN | 포인트 value = null. 해당 배열 인덱스로 근거를 지정 | DATA_GAP |
| ARK_PASSIVE_DISABLED | enabled = false | SETTING_CHECK |
| ARK_GRID_GEM_INACTIVE | inactiveGemCount > 0 | SETTING_CHECK |
| ARK_GRID_GEM_STATE_UNKNOWN | unknownGemCount > 0 | DATA_GAP |

DATA_GAP은 정보가 부족하다는 뜻이며 게임 세팅의 오류를 의미하지 않는다. SETTING_CHECK 역시 의도적인 세팅일 수 있어 확인 대상으로만 표시한다. 미확인과 비활성은 동시에 존재할 수 있다.

장비·보석·카드·각인·패시브·그리드 순서로 결정적으로 출력한다. 우선순위나 위험도 순서가 아니다. 정상적인 0 각성, 0 포인트, 낮은 보석 레벨은 임의의 기준으로 부족 판정을 하지 않는다. `findings: []`도 모든 스펙이 완성됐다는 뜻은 아니다.

## 캐시, 시각, 외부 호출

`observed-v2`는 원천 미제공 목록과 빈 목록을 구별한다. 신규 목록 미제공 코드는 `/data/coverage/...`를 근거로 가리키며 observedCount는 null이다. 예를 들어 GEM_ITEMS_UNAVAILABLE의 근거는 `/data/coverage/gems/collections/items`다. 실제 빈 목록 또는 필터링 후 항목이 없는 목록은 기존 NOT_OBSERVED 코드를 유지한다. `[null]`은 원천 PROVIDED이지만 유효한 관측 개수는 0일 수 있다.

진단 응답 필드 구조는 그대로이며 새 코드 4개와 규칙 버전이 추가되었다. 클라이언트는 고정된 코드 전체 목록을 전제하지 말고 알 수 없는 코드도 표시용 message와 category를 처리할 수 있어야 한다. 미제공 섹션은 기존 UNAVAILABLE 규칙을 유지한다. 깊은 하위 배열과 각인/패시브의 모든 내부 목록에 새 경고를 추가한 것은 아니다.

- 캐시의 CharacterResult에서 응답을 만들 때 순수 함수로 계산한다. 규칙 변경 시 기존 캐시에서도 현재 규칙이 적용된다.
- `fetchedAt`은 원천 데이터를 수집한 시각이다. 진단 생성 시각 또는 게임 서버의 마지막 갱신 시각이 아니다.
- 진단을 위해 추가 외부 API 요청이나 DB 조회를 하지 않는다. 기존 90회/분 요청 예산과 single flight를 유지한다.
- 규칙의 의미가 변경되면 ruleVersion을 변경한다. 외부 API 데이터 누락과 실제 미착용의 구별, DPS/레이드 적합성/강화 비용 계산은 범위 밖이다.

## 검증

도메인 테스트는 미제공·빈 목록·알 수 없는 값·비활성·정상 0·비정상 원본의 해석을 검증한다. HTTP 통합 테스트는 근거 경로가 응답에 존재하고 캐시 전후 진단이 같은지 검증한다. Java OpenAPI 테스트는 주요 필수 필드를 확인한다. 전체 JSON Schema 대조는 로컬 선택 검사이며 CI에서는 Java 테스트를 실행한다. 테스트는 실제 Lost Ark API를 호출하지 않는다.

설계 배경은 [비교 사이트 분석](reference-sites.md), 기존 집계는 [캐릭터 기본 분석](character-analysis.md)을 참고한다.
