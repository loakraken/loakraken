# 원천 데이터 제공 상태

검색 응답의 `data.coverage`는 정규화 전 응답 구조를 기록한다. 제공 여부와 게임 속 상태는 별개다.
기존 `loadout`의 빈 배열 계약은 유지하며 관측 상태를 옆에 추가한다.

| 상태 | 의미 |
|---|---|
| NOT_PROVIDED | 해당 필드가 누락되었거나 null이다. 둘을 따로 구분하지 않는다 |
| PROVIDED_EMPTY | 원천 목록이 실제 빈 배열이다. 미착용을 확정하지 않는다 |
| PROVIDED | 객체 또는 비어 있지 않은 원천 목록이 제공되었다. 모든 항목의 유효성을 보증하지 않는다 |

장비·원정대는 목록 상태를 직접 표현한다. 나머지는 `state`로 객체 제공 여부를, `collections`로 내부의 주요 목록 상태를 표현한다. 빈 객체 `{}`도 PROVIDED이며 객체에 PROVIDED_EMPTY는 사용하지 않는다.

| coverage 필드 | 원천 | collections의 고정 키 |
|---|---|---|
| equipment | ArmoryEquipment | 없음 |
| engravings | ArmoryEngraving | slots / effects / arkPassiveEffects |
| gems | ArmoryGem | items |
| cards | ArmoryCard | items / effects |
| arkPassive | ArkPassive | points / effects |
| arkGrid | ArkGrid | slots / effects |
| siblings | 원정대 응답 배열 | 없음 |

섹션이 미제공이어도 collections의 고정 키를 모두 포함하고 각각 NOT_PROVIDED로 표현한다.

| 원천 보석 응답 | coverage.gems.state | coverage.gems.collections.items |
|---|---|---|
| ArmoryGem: null | NOT_PROVIDED | NOT_PROVIDED |
| ArmoryGem: {Gems: null} | PROVIDED | NOT_PROVIDED |
| ArmoryGem: {Gems: []} | PROVIDED | PROVIDED_EMPTY |
| ArmoryGem: {Gems: [{...}]} | PROVIDED | PROVIDED |

범위는 표의 섹션과 주요 최상위 목록이다. 보석 효과의 스킬별 설명, 카드 세트 내부 목록, 그리드 슬롯별 젬 등의 깊은 하위 배열까지 구분하는 모델은 아직 아니다. 스칼라 값의 유효성은 기존 분석 규칙에서 별도로 다룬다. `[null]`은 비어 있지 않으므로 PROVIDED이지만 null 항목을 걸러낸 실제 분석 개수는 0일 수 있다.

두 외부 요청 모두 성공해야 검색 성공이다. 원정대 응답 전체가 null이면 외부 오류이므로 성공 응답에서 siblings=NOT_PROVIDED는 현재 나오지 않는다. 섹션 누락만으로 PARTIAL이나 FAILED를 붙이지 않는다.

원천 DTO에서 목록 정규화 전에 계산하고 CharacterDetails와 함께 캐시에 저장한다. 캐시 왕복과 재조회에서 상태와 fetchedAt을 보존한다. 키는 `character:v3:{name}:summary`로 변경했다. 원천 상태가 없는 v2 데이터는 읽지 않고 기존 TTL로 만료되며 임의로 상태를 추정하지 않는다. 캐시가 v3여도 coverage가 없는 불완전한 값은 기존 손상 캐시 처리로 다시 수집한다. 이후 표시용 텍스트 정제를 적용하며 키를 `character:v4:{name}:summary`로 변경했고 마크업이 남은 v3 데이터는 읽지 않는다. 장비 Tooltip 해석 필드를 추가하며 키를 `character:v5:{name}:summary`로 변경했다. 연마 등급(equipment-tooltip-v2)을 추가하며 `character:v6`으로 변경했다. 팔찌 등급(equipment-tooltip-v3)을 추가하며 `character:v7`로 변경했다.

검증은 독립 보석 fixture 4종, 모의 HTTP 조회, 직렬화 왕복, 구버전 캐시 배제, Swagger 필수 필드 검증으로 수행한다. 실제 Lost Ark API는 추가 호출하지 않는다. 스냅샷 저장 정책과 스키마는 변경하지 않는다.
