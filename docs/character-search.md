# 캐릭터 검색

`GET /api/v1/characters/search?characterName=모험가`

프로필과 장비·보석·각인·카드·아크패시브·아크그리드, 원정대 캐릭터를 한 번에 제공합니다.
이름은 앞뒤 공백 제거와 Unicode NFC 정규화 후 조회하며 문자·숫자 1~64자를 허용합니다.
성공 응답은 공통 봉투 안에 캐릭터명, 서버, 직업, 레벨, 아이템 레벨, 전투력,
원정대 레벨, 이미지, 칭호, 길드, 능력치, 전투정보 `loadout`, 원정대 `siblings`와 수집 완료 시각 `fetchedAt`을 담습니다.
공식 API의 쉼표 포함 숫자는 숫자형으로 변환하고 Tooltip과 외부 응답 전문은 노출하지 않습니다.
칭호·길드와 전투정보의 이름·설명·옵션은 공식 응답의 표시용 마크업(`<FONT>`, `<P>`, `<IMG>` 등)을 제거한 일반 텍스트입니다. `<BR>`은 줄바꿈으로 바꾸고 기본 HTML 엔티티를 복원하며, 태그가 아닌 `<` 문자는 유지합니다. 색상 강조는 제공하지 않으며 원본 JSON 스냅샷에는 정제를 적용하지 않습니다.

## 조회와 캐시

- Valkey 키: `character:v11:{정규화한 캐릭터명}:summary`, 기본 TTL 5분 (`lostark.summary-ttl`)
- 전투정보와 원정대 조회가 모두 성공한 결과만 캐시에 저장합니다. 두 요청은 순서대로 실행하며 각각 호출 예산을 사용합니다.
- 기본 설정에서는 수집 결과를 단기 캐시에만 반영합니다. `CHARACTER_SNAPSHOTS_ENABLED=true`일 때만 [PostgreSQL 스냅샷](character-snapshots.md)에 먼저 저장합니다.
- 캐시 적중 시 최초 수집 시각을 유지하고 외부 API를 호출하지 않습니다.
- `refreshAvailableAt`: 캐시 만료 예정 UTC 시각(저장 직전 시각 + `lostark.summary-ttl`). 이 시각 이전 요청은 캐시를 반환해 `fetchedAt`이 바뀌지 않는다. 캐시 적중에서도 같은 값을 유지하며 실제 Valkey 만료는 1초 미만 늦을 수 있다. 이전 캐시 값처럼 알 수 없으면 null. 원정대 조회(`/siblings`) 응답에도 같은 의미로 제공한다. 프론트는 이 시각 전 갱신 버튼을 비활성화하는 용도로 쓴다(일로아의 "N분 후 다시 갱신" 동작 참고).
- 같은 이름의 동시 요청은 단일 애플리케이션 인스턴스 안에서 한 번으로 합칩니다.
- 손상된 캐시는 삭제 후 조회하며 캐시 연결 장애 시 503으로 종료합니다.
- 기본 연결 제한 3초, 읽기 제한 5초, 분당 최대 90회, 동시 외부 호출 최대 4회입니다.
- 공식 API의 잔여 요청량과 초기화 시각을 반영하고 자동 재시도하지 않습니다.
- 호출 제한과 동시 요청 병합은 인스턴스 단위입니다. MVP는 동일 키를 쓰는 백엔드 프로세스 1개를 전제로 하며, 여러 인스턴스 운영 전 공유 제한이 필요합니다. [운영 지표와 배포 전제](backend-operations.md)를 참고합니다.

설정은 `lostark` 속성에서 변경합니다. API 키는 서버 환경 변수 `LOSTARK_API_KEY`로 전달합니다.

## 전투정보

`coverage`는 정규화 전 주요 섹션과 목록의 제공 상태다. [원천 제공 상태 계약](character-observation.md)의 null·빈 배열·제공 구분을 사용한다. 빈 배열은 미착용 판정이 아니다.

`loadout`은 `equipment`, `engravings`, `gems`, `cards`, `arkPassive`, `arkGrid`를 포함합니다.
목록 데이터가 없으면 빈 배열, 개별 섹션 데이터가 제공되지 않으면 `null`입니다.
`null`을 장비 미착용이나 기능 비활성화로 단정하지 않습니다. 예를 들어 `arkPassive.enabled=false`와
`arkPassive=null`은 서로 다른 상태입니다. 문자열은 외부 서비스의 이름·설명을 보존하므로 화면에서는
텍스트로 표시해야 하며 HTML로 삽입하지 않습니다.

장비 재련·품질 등은 아래 Tooltip 해석 규칙으로 제공하며 성능 점수는 아직 제공하지 않습니다.
캐시 키 버전을 분리했으므로 기존 프로필 캐시가 종합 응답으로 사용되지 않습니다.
원정대 목록은 공식 Siblings 응답 전체이며 서버로 임의 필터링하지 않습니다.
`analysis`는 [기본 분석](character-analysis.md)의 관측값 집계를 제공합니다.

## 원정대 분리 조회

공식 전투정보실은 원정대 캐릭터를 접힌 목록으로, 로아와·클로아·일로아는 보유 캐릭터를 별도 탭/페이지로 제공한다(2026-10-05 관찰). 이에 맞춰 원정대를 검색과 따로 조회할 수 있다.

- `GET /api/v1/characters/search?includeSiblings=false&characterName=모험가`: 공식 Armories만 1회 호출한다. 응답 `siblings`와 `coverage.siblings`는 null이며 "이 요청에서 조회하지 않음"을 뜻한다. 캐시 키는 `character:v11:{name}:armory`. 스냅샷은 저장하지 않는다(스냅샷 스키마가 원정대 원문을 필수로 요구한다).
- `includeSiblings` 생략 또는 true: 기존과 같다. Armories + Siblings 2회, 둘 다 성공해야 검색 성공, 캐시 키 `character:v11:{name}:summary`.
- `GET /api/v1/characters/siblings?characterName=모험가`: 공식 Siblings만 1회 호출한다. 응답은 `characterName`, `siblings`, `fetchedAt`, 성공 코드 `CHARACTER_SIBLINGS_SUCCESS`. 캐시 키 `character:v1:{name}:siblings`, 기본 TTL은 검색과 같은 5분. 같은 이름의 동시 요청은 인스턴스 안에서 한 번으로 합친다.
- 공식 원정대 API는 없는 캐릭터에 200 `[]`를 반환한다(2026-10-05 1회 확인). 원정대에는 조회한 캐릭터 자신이 포함되므로 단독 원정대 조회의 빈 목록과 공식 404는 `404 CHARACTER_NOT_FOUND`다. 결합 검색에서는 전투정보 확인 뒤의 원정대 오류를 기존처럼 502로 처리한다.
- 화면을 나누면 캐릭터 상세는 1회, 원정대 탭을 열 때 1회를 사용한다. 원정대 실패가 캐릭터 상세를 막지 않는다.

## 장비 Tooltip 해석 — equipment-tooltip-v4

각 `equipment[].detail`은 공식 장비 Tooltip(JSON 문자열)에서 확인된 값만 구조화한다. Tooltip이 없거나 JSON이 아니거나 객체가 아니면 `detail=null`이며 검색은 실패하지 않는다. 원본 Tooltip 문자열은 공개 응답과 캐시에 포함하지 않는다.

| 필드 | 원천 | 규칙 |
|---|---|---|
| parserVersion | 고정값 | `equipment-tooltip-v4` (v2 연마 등급, v3 팔찌 등급, v4 아이템 툴팁 줄) |
| refineLevel | 장비 이름의 `+N` 접두 | 1~99, 없으면 null |
| quality | 첫 `ItemTitle.qualityValue` | 0~100 정수. `-1`(품질 미적용)·문자열·범위 밖은 null |
| itemLevel | 첫 `ItemTitle.leftStr2`의 `아이템 레벨 N` | 장신구처럼 표시되지 않으면 null |
| tier | 같은 문자열의 `티어 N` | 없으면 null |
| arkPassivePoint | 제목이 `아크 패시브 포인트 효과`인 `ItemPartBox` | `이름 +값` 한 줄일 때만 `{name, value}`, 그 외 null |
| effects | 그 밖의 `ItemPartBox`(기본/추가/연마/팔찌 효과)와 `IndentStringGroup` | `{title, lines}`. 마크업을 제거하고 `<BR>` 기준 줄로 나눈다 |
| stoneEngravings | `IndentStringGroup` 제목 `무작위 각인 효과` | `[이름] Lv.N` 형식만 `{name, level}`. 레벨 보너스 줄은 effects에만 남는다 |
| polishEffects | 제목이 `연마 효과`인 `ItemPartBox`의 각 줄 | `{name, value, grade}`. `이름 +값` 형식만. grade는 수치 색 `FE9600`=HIGH, `CE43FC`=MID, `00B5FF`=LOW, 그 외 null |
| titleLine | 첫 `ItemTitle.leftStr0` | 등급·부위 줄 원문(예 "고대 머리 방어구"), 없으면 null |
| tradeLines | `ItemTitle` 바로 뒤 `SingleTextBox`와 이어지는 `MultiTextBox` | 귀속·거래 줄 원문 순서(예 "캐릭터 귀속됨", "거래 제한 아이템 레벨 1680", "거래 2회 가능", "거래가능") |
| refineExp | 제목에 `재련 경험치`가 있는 `Progress` | `{current, max}`(현재 값이 없으면 current null), 없으면 null |
| braceletEffects | 제목이 `팔찌 효과`인 `ItemPartBox` | `{text, grade}`. 효과 앞 아이콘 기준으로 나눠 두 줄 효과를 하나로 유지. grade는 효과의 첫 색 수치(연마와 같은 색 대응), 전투 특성 초록 `91FE02` 등은 null |

- 연마 등급 근거: [공식 장신구 연마 확률표](https://lostark.game.onstove.com/Probability/장신구%20연마)(수정일 2024-07-10)는 효과마다 3단계를 색으로 구분하고 등장 확률 6.3%(파랑 `00B5FF`)·3.0%(보라)·0.7%를 공개한다. 2026-10-05 언새늘 원문의 색과 로아와·클로아·일로아의 상/중/하 표기가 같은 대응을 보였다. 팔찌 등급 근거: [공식 팔찌 T4 확률표](https://lostark.game.onstove.com/Probability/팔찌%20T4)(수정일 2025-12-30)는 유물·고대 팔찌 특수 효과마다 3단계 수치를 공개한다(고대 예: 치명타 피해 6.8/8.4/10.0%). 언새늘 고대 팔찌의 색이 같은 단계와 대응했다. 전투 특성(특화 등)은 공식 표에서 수치 구간형이라 등급을 만들지 않는다.
- 효과 줄은 표시용 문자열이다. 능력치 이름과 수치로 분해하거나 합산하지 않는다.
- 알 수 없는 요소 타입은 무시한다. 엘릭서·초월·상급 재련은 실제 응답 표본을 확보하지 못해 해석하지 않으며, 해당 정보가 없다는 뜻이 아니다.
- 해석 대상 요소는 2026-10-05 실제 응답 1건의 장비 17개 구조를 기준으로 했다. 실제 응답은 저장하지 않았고 테스트는 같은 구조의 합성 Tooltip을 사용한다.
- 점수·추천·투자 효율은 제공하지 않는다.
- 아이템 hover 툴팁용 줄(v4, PM-프론트·PM-백엔드 교차 확인 2026-10-10): 검색 응답에는 짧은 필드(`titleLine`·`tradeLines`·`refineExp`, 스킬 `tripods[].effect`·`rune.effect`)만 둔다. 공식 Tooltip의 나머지 줄(`extraLines`: 내구도·분해불가·설명·획득처 등, `|` 칸과 줄바꿈 기준)은 캐릭터 검색 gzip을 +15% 넘게 키워(실측 +20.8~23.4%) `GET /api/v1/characters/item-tooltips?characterName=`로 나눴다. 이 경로는 캐릭터 캐시만 읽고 공식 호출이 없으며, 캐시가 없으면 404 `CHARACTER_NOT_CACHED`다. 검색 응답과는 장비 순번(index)·type, 보석·카드 slot, 스킬 name으로 잇는다. 공개 제외면 `hidden=true`·빈 목록. 이전 캐시(최대 5분)는 새 필드가 null·빈 목록이다.

## 전투 스킬

공식 Armories 통합 조회의 필터에 `combat-skills`를 포함한다. 외부 호출 수는 늘지 않는다(2026-10-05, 프론트 요청·일로아 전투 탭 참고).

- `data.skillPoints`: 공식 프로필 `UsingSkillPoint`/`TotalSkillPoint`를 `{used, total}`로. 둘 다 없거나 음수면 null.
- `data.loadout.skills[]`: 원천 스킬 전부(기본 스킬 포함). `{name, icon, level, type, partBreakLevel, counter, tripods[{tier, slot, name, icon}], rune{name, icon, grade}, gemEffects[{text, kind}], attackType}`.
  - `partBreakLevel`: 스킬 Tooltip의 `부위 파괴 : 레벨 N`, 없으면 null. `counter`: `카운터 : 가능`이 있으면 true, 없으면 false, Tooltip 해석 불가면 null.
  - `attackType`: Tooltip의 `공격 타입 : 백 어택|헤드 어택`(콜론 앞뒤·어택 사이 공백 차이 허용) → BACK_ATTACK·HEAD_ATTACK, 공격 타입 줄이 없으면 NONE. Tooltip을 해석할 수 없거나 "공격 타입" 줄은 있는데 값을 읽지 못하면 null(추정 금지).
    - 갱신 전 캐시(최대 5분)도 null이다.
    - 스킬별 치명타 적중률(클로아)은 `crit.observedRate` + BACK_ATTACK이면 10%p다(계산 검증 2026-10-09: 언새늘 8/8 일치).
  - `tripods`: 선택된 것만. 공식 응답에 트라이포드 레벨은 없다.
  - `gemEffects`: 스킬 Tooltip의 `보석 효과` 줄. `피해 N% 증가`=DAMAGE, `재사용 대기시간 N% 감소`=COOLDOWN, 그 외 null.
- `data.coverage.skills`: 스킬 목록 제공 상태.
- 일로아처럼 "부위 파괴 Lv×개수·카운터 개수"를 보이려면 사용 중인 스킬(레벨>1, 트라이포드·룬·보석 중 하나라도 있음)만 센다. 언새늘: 전체 24개 중 사용 8개, 부위 파괴 Lv1 ×1, 카운터 1, SP 482/483.
- Tooltip 원문은 응답·캐시에 넣지 않는다. 캐시 키 `character:v8`.

## 내실과 아바타

Armories 통합 조회 필터에 `avatars`·`collectibles`를 포함한다(외부 호출 수 그대로). 2026-10-05 언새늘 원문과 공식 Swagger로 구조를 확인했다.

- `tendencies[{type, point, maxPoint}]`: 지성·담력·매력·친절.
- `decorations{symbol, emblems[]}`: 공식 프로필 장식(심볼·문장). 없으면 null.
- `honorPoint`, `townName`, `townLevel`, `guildMemberGrade`.
- `avatars[{type, name, icon, grade, isSet, isInner, effects[]}]`: 덧입기는 `isInner=true`, `effects`는 Tooltip의 기본 효과 줄. 염색 정보는 공식 응답에서 확인되지 않아 제공하지 않는다.
- `collectibles[{type, icon, point, maxPoint, items[{name, point, maxPoint}]}]`: 수집품 11종. 획득처는 공식 API에 없다.
- `coverage.avatars`·`coverage.collectibles`. 증명의 전장(colosseums)은 아직 제공하지 않는다.
- 캐시 키 `character:v11`.

## 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | 이름 누락 또는 형식 오류 |
| 404 | CHARACTER_NOT_FOUND | 공식 API 404 또는 빈 프로필. 같은 이름은 5분 동안 공식 호출 없이 404(#108) |
| 429 | CLIENT_RATE_LIMITED | 이 클라이언트의 분당 요청·공식 호출 한도 초과(#108) |
| 429 | LOST_ARK_RATE_LIMITED | 호출량·동시 호출 제한 또는 공식 API 429 |
| 502 | LOST_ARK_API_ERROR | 공식 API 오류, 잘못된 응답, 연결 실패 |
| 503 | LOST_ARK_NOT_CONFIGURED | 서버 API 키 미설정 |
| 503 | CHARACTER_CACHE_UNAVAILABLE | 캐시 읽기·쓰기 실패 |
| 503 | CHARACTER_STORAGE_UNAVAILABLE | 관측 저장 활성 상태에서 수집 결과 DB 저장 실패 |
| 504 | LOST_ARK_API_TIMEOUT | 공식 API 응답 시간 초과 |

외부 오류 본문과 인증 정보는 응답에 포함하지 않습니다.
테스트는 로컬 HTTP 모의 서버와 실제 Valkey Testcontainers를 사용합니다.
공식 서비스에 대한 실제 키 호출은 별도 확인이 필요합니다.

공식 계약: [Armories Swagger](https://developer-lostark.game.onstove.com/swagger-doc/endpoints/armories),
[사용 가이드](https://developer-lostark.game.onstove.com/usage-guide).

## 스탯 Tooltip과 아크 패시브 효과 문구 (2026-10-06)

- `stats[].tooltip`: 공식 스탯 Tooltip 문장을 마크업을 제거해 그대로 제공한다. 예: `치명` → `치명타 적중률이 20.61% 증가합니다.` 빈 줄은 뺀다.
- `loadout.arkPassive.effects[].effect`: 공식 노드 ToolTip의 `MultiTextBox` 효과 문구(수치 포함)를 한 줄로 이은 값이다. 해석할 수 없으면 null이다. 예: `치명타 적중률이 8.0% 증가하고, 진화형 피해가 10.0% 증가합니다.`
- 두 값은 [치명타·공이속 계산](crit-speed.md)의 관측 근거로 쓰인다. 캐시 키는 `character:v11`.

## 아크 그리드 상세 (arkgrid-tooltip-v1, 2026-10-08)

프론트 요청 F-4(일로아 전투 탭 대조)에 따라 공식 코어·젬·효과 Tooltip을 해석한다. 해석할 수 없으면 해당 필드는 null이거나 빈 목록이다. 수치를 읽을 수 없는 효과 문자열은 노출하지 않는다.

- `arkGrid.effects[]`: `{name, level, value, percent}`. 예: `공격력` Lv.28 → `value="공격력 +1.02%"`, `percent=1.02`
- `arkGrid.slots[]`에 추가된 필드
  - `coreType`: 예 `질서 - 해`
  - `supplyWillpower`: 코어 공급 의지력
  - `usedWillpower`: 활성 젬의 필요 의지력 합
  - `options[{threshold, text, active}]`: `[10P]`·`[14P]`… 구간 옵션. `active`는 현재 포인트로 달성했는지다.
  - `activeOptionCount`·`optionCount`: 일로아의 "4/6"에 해당한다.
  - `conditions[]`: 코어 옵션 발동 조건
- `arkGrid.slots[].gems[]`에 추가된 필드
  - `name`, `gemType`
  - `gemPoint`: 젬 기본 정보의 젬 포인트
  - `corePoint`: 코어에 더하는 질서·혼돈 포인트
  - `requiredWillpower`, `baseWillpower`, `willpowerEfficiency`
  - `options[{name, level, effect, percent}]`
- `arkGrid.willpower{used, supply}`: 코어별 값의 합이다. 언새늘 실서버 91/92로 일로아와 같다.
- 어빌리티 스톤 세공 보너스(F-5)는 이미 `equipment[].detail.effects`에 있다.
  - "세공 단계 보너스" 그룹
  - "무작위 각인 효과" 그룹의 `[레벨 보너스] 기본 공격력 +1.50%` 줄
- 캐시 키는 `character:v11`이다.
