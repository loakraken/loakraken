# 캐릭터 공개 요약 (#145)

PM 총괄 결정(출시 때 캐릭터 페이지 조건부 색인 허용)과 PM-프론트 회신(10-09 20:35)을 따른다.

`GET /api/v1/characters/public-summary?characterName=`

- **공식 API를 부르지 않는다.** 캐릭터 캐시(5분, `LIVE_CACHE`) → DB의 마지막 관측(`STORED_OBSERVATION`) 순서로 찾는다. 둘 다 없으면 `404 CHARACTER_NOT_CACHED`다.
- 응답: `{characterName, serverName, className, characterLevel, itemLevel, combatPower, arkPassiveTitle, observedAt, dataSource, indexable}`
  - 저장된 관측: characters 행(서버·직업·아이템 레벨·전투력)과 최신 장비 요약(#165)의 레벨·각성. `observedAt` = 검색·수집·원정대 확인 중 가장 늦은 시각
- 처리정지 목록(`public_exclusion`) 캐릭터는 저장된 관측을 쓰지 않는다. 캐시가 있으면 일반 캐릭터와 같은 `LIVE_CACHE` 응답이고, 없으면 기록 없는 캐릭터와 같은 404다(#363, 목록에 있다는 사실을 드러내지 않음).
- 전투력 관측이 없는 캐릭터(원정대 목록에서만 발견)는 값은 그대로 주되 `indexable=false`다(#180).
- 웹 사용(PM-프론트 조건)
  - 클로킹 방지: 봇·사람 구분 없이 이 요약으로 첫 HTML(제목·설명·요약 줄·구조화 데이터)을 만들고, 사람은 그 뒤 검색 API로 상세를 채운다. 그래서 백엔드는 User-Agent를 보지 않는다.
  - `indexable=false`와 404는 noindex·sitemap 제외
  - `STORED_OBSERVATION`이면 `observedAt`을 "마지막 확인 시각"으로 보인다
- 유입 제한: 일반 요청과 같은 버킷(#151). 봇 표시 요청은 봇 공유 버킷(≤ 일반)을 쓴다.
