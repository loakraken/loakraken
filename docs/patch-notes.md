# 패치 이력 1단계 (patch-notice-v1)

일로아 `/patch-notes`의 목록 뼈대를 흡수한다. 날짜, 제목, 분류, 공식 원문 링크를 준다. 근거는 볼트 `40 레퍼런스/패치 이력 데이터 출처 조사.md`이고, PM 총괄이 배정했다.

## 출처와 범위

- 공식 Open API `/news/notices`를 쓴다. 홈 소식(#88)과 같은 호출·캐시라 **공식 호출이 늘지 않는다**.
- 공식 API는 최근 약 3개월만 준다. 2026-10-09 확인 결과는 70건, 2026-07-09~10-09이다.
  - 공지 41, 점검 19, 이벤트 4, 상점 6
  - 그래서 소식을 새로 받을 때마다(10분 캐시 미스) 패치 공지를 공지 번호로 중복 제거해 `patch_notice`(V10)에 쌓는다.
  - 저장은 응답 경로 밖의 단일 작업자가 배치로 한다. 그래서 소식을 처음 받은 직후(빈 DB나 새 공지)에는 `/home/patch-notes`가 잠깐 비어 있거나 막 받은 공지가 빠져 보일 수 있고, 작업자가 쓰는 즉시(보통 1초 안) 반영된다.
  - 관찰자 실패는 소식 응답을 깨지 않는다(PM-백엔드 #126 P2).
  - 그보다 앞선 이력은 관측을 시작한 뒤부터 쌓인다.
  - 응답의 `oldestPublishedAt`·`firstObservedAt`으로 범위를 알린다.
- 본문은 수집하지 않는다. 공식 홈페이지 본문 수집과 구조화는 2단계이고, PM 총괄의 이용약관 확인 뒤에 한다.
- 링크는 `https://lostark.game.onstove.com/News/Notice/Views/{번호}`만 받는다. 공식 도메인 검증과 번호 형식 검사를 거친다.

## 분류 규칙 (제목, 위에서부터 먼저 맞는 것)

| 분류 | 제목에 포함 | 예 |
|---|---|---|
| UPDATE | 업데이트 내역 안내 | 10월 7일(수) 업데이트 내역 안내, (내용 추가) 포함 |
| BALANCE | 밸런스 | 클래스 밸런스 조정 안내 |
| CLIENT_PATCH | 클라이언트 패치 안내 | 10월 8일(목) 클라이언트 패치 안내 |
| MAINTENANCE_COMPLETE | 점검 완료 안내 | 10월 9일(금) 로스트아크 임시 점검 완료 안내 |

- 분류하지 못한 공지는 패치가 아니므로 저장하지 않는다. 점검 예고, 상점, 이슈 안내, 리샤의 편지 등이 해당하며, 홈 소식 API에는 그대로 나온다.
- "기타"로 남기지 않는 이유: 패치 목록에 섞이면 일로아식 패치 목록과 의미가 달라진다.
- 같은 공지 번호의 제목이 바뀌면 최근 관측으로 바꾼다(예: "(내용 추가)").

## `GET /api/v1/home/patch-notes?category=&page=1&size=20`

- 응답
  - ruleVersion, category, page, size, total
  - oldestPublishedAt, firstObservedAt, sourceFetchedAt
  - items[]: {noticeId, title, category, publishedAt, url}
- 정렬은 게시 시각 내림차순이다. size는 최대 50, page는 최대 100이다.
- 오류: 400 INVALID_REQUEST, 503 HOME_CACHE_UNAVAILABLE·HOME_STORAGE_UNAVAILABLE
