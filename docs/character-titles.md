# 원정대 보유 칭호 (title-record-v1)

PM 총괄 배정 E와 클로아 "획득한 특수 칭호"(3곳)를 흡수한다.

## 출처

- 공식 Open API는 **현재 장착 칭호**(`ArmoryProfile.Title`) 하나만 준다. 보유 칭호 목록과 획득일은 없다.
  - 2026-10-09 언새늘 armory 실조회로 확인했다.
  - armory 최상위 섹션 11개와 프로필 필드 19개를 살폈다. `Decorations`는 Symbol·Emblems 모두 null이었다.
- 그래서 관측 기준으로 준다. 사용자 검색과 통계 수집기가 프로필을 받을 때 장착 칭호를 `character_title_observation`에 기록한다(처음·마지막 확인 시각).
- 생활 레벨 6종(식물 채집·벌목·채광·수렵·낚시·고고학)도 공식 API에 없다. 공식 홈페이지 수집은 이용약관 확인 대상이라 하지 않는다.

## `GET /api/v1/characters/titles?characterName=`

- 원정대는 `/siblings` 캐시를 공유한다. 칭호는 원정대가 공유하므로 같은 서버 원정대로 묶는다.
- 칭호마다 가장 먼저 확인한 기록 하나를 쓴다. `firstObservedAt` 오름차순이고, `observedOrder`는 1부터 매긴다.
- 응답
  - characterName, serverName, rosterCharacters, observedCharacters, ruleVersion
  - titles[]: {title, firstObservedAt, firstObservedDate(KST 날짜, #175), lastObservedAt, observedOrder, recordedBy, source}
  - 같은 `firstObservedDate`의 칭호는 한 번의 관측(첫 원정대 기록)에 함께 들어온 것이라 `observedOrder`의 앞뒤에 의미가 없다. 화면은 같은 날짜를 묶어 "같은 날 확인"으로 보인다.
- **노출 기준(PM 총괄)**
  - "LOAKraken에서 처음 확인한 순서다. 장착한 칭호만 기록되며 실제 획득일과 다를 수 있다."
  - `acquiredAt`·획득 순서 표현은 쓰지 않는다. 프론트 표기는 "처음 확인"이다.
- `source`는 지금 `OBSERVED`뿐이다. 공식 보유 목록이 생기면 `OFFICIAL`을 우선한다.
- 특정 칭호를 가진 캐릭터 목록 API는 만들지 않는다. 만들게 되면 `public_exclusion`을 적용한다.
- 특수 칭호 설명(예: "카멘 The FIRST 난이도 클리어 보상")은 공식 응답에 없다. 출처를 검증하기 전까지 넣지 않는다.

| 오류 | 상황 |
|---|---|
| 400 INVALID_REQUEST | 이름 형식 |
| 404 CHARACTER_NOT_FOUND | 공식 원정대에 없음 |
| 429·502·504 | 원정대 공식 호출 실패 |
| 503 CHARACTER_HISTORY_DISABLED·CHARACTER_HISTORY_UNAVAILABLE | 관측 이력 꺼짐·DB 오류 |
