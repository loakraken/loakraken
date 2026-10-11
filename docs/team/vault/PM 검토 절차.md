<!-- 옵시디언 볼트 사본(2026-10-12 07:20 기준, 원본: 30 PM/PM 검토 절차.md). 고칠 때는 볼트 원본을 고치고 PM 총괄이 다시 복사한다. -->

---
date: 2026-10-08
type: convention
tags: [loakraken, pm, review]
---

# PM 검토 절차

관련: [[작업 규칙]], [[커밋과 PR 규칙]], [[팀 간 요청]], [[PM 검토 기록]]

## 사용자 직접 지시 — 2026-10-08

- 별도 채팅이 **프로젝트 매니저(PM)** 를 맡는다. 세션 이름은 `프로젝트 매니저 역할 및 검증`이다.
- PM은 세 가지를 본다.
  - 목표대로 가고 있는가: 모든 정보 사이트 기능 흡수, 목표 사이트 90% 따라가기
  - 무엇이 부족한가
  - 사용자가 보기에 어색하거나 거부감이 들 만한 것
- 프론트·백엔드 채팅은 **PR을 올리면** PM에게 검토를 요청한다. PM은 gstack과 검증 스킬로 분석·검증한다.

## PM 3인 체제 (사용자 지시 2026-10-08)

| PM | 세션 | 맡는 일 |
|---|---|---|
| **PM 총괄** | `프로젝트 매니저 역할 및 검증` | 사용자와 직접 대화하는 창구다. 제품 결정, 우선순위, 팀 구성, 스킬 요청 검토·설치, PM 간 이견 조정, 레퍼런스팀 결과 분류를 맡는다. |
| **PM-프론트** | 신규(이름에 "PM 프론트" 포함) | 프론트·디자인 시스템·QA(e2e) PR을 검토하고, 화면 시안에 의견을 낸다. |
| **PM-백엔드** | 신규(이름에 "PM 백엔드" 포함) | 백엔드·계산 검증 PR을 검토하고, 계약·호출 예산·보안을 확인한다. |

- PR 검토 요청은 **반드시 담당 PM**에게 보낸다(사용자 지시 2026-10-08). PM 총괄에게 보내지 않는다.
  - 프론트엔드·디자인 시스템·QA PR → **PM-프론트** (세션 `PM 프론트 담당 검토 시작하기`)
  - 백엔드·계산 검증 PR → **PM-백엔드** (세션 `PM 백엔드 담당 검토 시작하기`)
  - PM 총괄이 PR 검토 요청을 잘못 받으면 담당 PM에게 넘긴다.
- 다음은 PM 총괄에게 보낸다. 담당 PM이 판단할 수 없는 것도 총괄로 올린다.
  - 제품 결정(이름, 범위, 디자인 원칙 해석)
  - 팀 간 충돌
  - 새 스킬 요청(PM 총괄이 확인해 필요하면 설치한다)
  - 사용자 확인이 필요한 일
- 담당 PM은 판정마다 [[PM 검토 기록]]에 한 줄을 추가한다. P1 이상이나 방향 문제는 PM 총괄에게 요약해 보낸다.
- 프론트와 백엔드 양쪽에 걸친 계약 변경은 PM-프론트와 PM-백엔드가 서로 확인한다. 이견이 있으면 PM 총괄이 정한다.

## 흐름

1. 작업 채팅이 PR을 올리고 자체 검증(lint·build·test·CI)을 마친다.
2. 담당 PM 세션에 `SendMessage`로 검토를 요청한다. 주소는 `ListAgents`로 확인한다.
3. 회신을 기다리는 동안 **다음 작업을 다른 브랜치에서 시작한다.** 멈추지 않는다.
4. PM이 판정을 회신한다.
   - **승인** → 바로 병합한다.
   - **수정 필요** → P0·P1을 같은 PR에서 고친 뒤 병합한다. 수정 내용을 짧게 회신한다. 재검토가 필요하면 PM이 판정에 적는다.
   - P2 → 병합을 막지 않는다. 후속 이슈로 만들거나 PM 검토 기록에 남긴다.
   - **조건부 승인은**(예: "계산 검증 회신 뒤", "#161 다음") 조건이 모두 채워졌다는 담당 PM의 회신("병합 가능")이 오기 전까지 병합하지 않는다. CI 통과 조건만 있는 승인은 CI 성공 뒤 바로 병합해도 된다(2026-10-09, #174 조기 병합 재발 방지).
5. **PR은 항상 담당 PM 판정을 받은 뒤 병합한다**(2026-10-09 변경).
   - 예전 "30분 무회신 시 CI 기준 병합" 규칙은 없앴다. 사용자 권한 설정이 검토 없는 병합을 막고 있고, 멈춘 PM은 세션 ID로 바로 깨울 수 있기 때문이다.
   - 담당 PM이 오래 답이 없으면 세션 ID로 다시 보낸다. 그래도 답이 없으면 PM 총괄에 알린다.

## 요청 메시지 형식

```
[PM 검토 요청] 프론트|백엔드 PR #번호 — 제목
- 링크: PR URL / 이슈 URL
- 목적: 어떤 목표 사이트의 어떤 기능인지
- 변경: 화면 경로 또는 API 엔드포인트, 계약 변경 여부
- 검증: lint/build/test/CI run, 확인한 화면 폭, 실제 API 호출 수
- 미완료·한계: 알고 있는 빈 곳
- 미리보기: 로컬 주소(실행 중이면), 스크린샷 경로
```

## 심각도

| 등급 | 뜻 | 처리 |
|---|---|---|
| P0 | 사용자가 바로 깨짐·잘못된 값·데이터 유출·비용 폭주를 겪음 | 병합 전 필수 수정 |
| P1 | 목표 사이트 대비 눈에 띄게 어색함, 잘못된 기대를 주는 문구, 계약 불일치 | 병합 전 수정 원칙 |
| P2 | 다듬기·개선 제안 | 후속 처리 |

## PM 검토 관점과 사용 스킬

- 목표 정렬: 해당 기능을 목표 사이트(언새늘 기준)와 비교한다. 빠진 하위 기능을 찾는다.
- 사용자 체감
  - 문구: 개발 용어(관측·진단·JSON Pointer·계약 등) 노출
  - 빈 상태·오류·로딩
  - 익숙한 관례(일로아·로아와)와 다른 점
- 프론트
  - 화면·사용성: `impeccable`(audit/critique), `web-design-guidelines`, `gstack-design-review`, `ui-ux-pro-max`, `ux-heuristics`(닐슨 10원칙·크루그)
  - 동작·접근성: `click-path-audit`(버튼·상태 흐름 끝까지 추적), `frontend-a11y`
  - 코드·성능: `vercel-react-best-practices`, `react-patterns`, `nextjs-turbopack`, `web-perf`
  - 브라우저 실화면(1440/768/390)
- 백엔드
  - `gstack-review`, `api-design-reviewer`, `senior-backend`
  - 보안: `gstack-cso`(보안·키), `springboot-security`(입력 검증·헤더·호출 제한·비밀값)
  - 데이터: `jpa-patterns`, `postgres-patterns`, `redis-patterns`, `database-migrations`(Flyway)
  - 호출 예산·OpenAI 비용
- 공통
  - `code-review`, `gstack-health`
  - `contract-first`: OpenAPI 계약과 프론트 생성 타입·목 데이터가 어긋나지 않는지 확인
  - 방향 점검: `gstack-plan-ceo-review`, `competitive-platform-analysis`(목표 사이트 분류·우선순위)
  - 출시 전 점검: `production-audit`

## 작업 채팅이 PR 전에 쓰는 스킬 (2026-10-08 설치)

ECC 저장소(affaan-m/ecc)에서는 스킬만 골라 설치했다. ECC 훅·통째 설치는 쓰지 않는다. tmux 강제, 수정마다 자동 포맷, 커밋 형식 검사가 우리 작업 방식과 충돌하기 때문이다.

| 채팅 | PR 올리기 전에 | 해당할 때 |
|---|---|---|
| 프론트 | `frontend-a11y`(펼침·탭·메뉴의 aria) | `react-patterns`·`nextjs-turbopack`(서버/클라이언트 경계, Next 16), `e2e-testing`(Playwright), `click-path-audit`(상태가 얽힌 화면) |
| 백엔드 | `springboot-verification`(빌드·테스트·비밀값 검사) | `springboot-security`(새 엔드포인트·입력), `jpa-patterns`·`postgres-patterns`(엔티티·쿼리·인덱스), `redis-patterns`(캐시·호출 제한), `database-migrations`(Flyway 마이그레이션) |
| 양쪽 | `contract-first`(계약 변경 시 OpenAPI·생성 타입·목 일치) | |

PM 검토 요청 메시지의 "검증" 줄에 사용한 스킬을 적는다.

### 스킬 필수 확대 (사용자 지시 2026-10-12: "스킬도 최대한 활용", [[사용자 지적 회귀 점검표]]와 짝)

PR 본문에 **'사용한 스킬'** 줄을 둔다. 아래 '필수' 스킬을 쓰지 않았으면 이유를 한 줄 적어야 한다. 담당 PM은 이 줄이 없으면 형식 반려한다.

| 상황 | 필수 | 해당할 때 |
|---|---|---|
| 화면 PR 전부(프론트·성장 도구·디자인 시스템) | `web-design-guidelines`(A 항목 전반), `frontend-a11y` | 새 화면·큰 개편은 `impeccable`(audit), 움직임은 `emil-design-eng`·`animate`, 문구는 `ux-copy`, 서버 데이터 화면은 `tanstack-query-best-practices`(A8 숫자 깜빡임), 상태가 얽힌 화면은 `click-path-audit` |
| e2e·테스트 PR | `playwright-best-practices` | 성능은 `web-perf` |
| 디자인 시스템 부품 | 위 + `tailwind-design-system` 또는 `apple-design` 중 해당 | 부품 이식은 원본 대조 캡처 |
| 백엔드·계산 PR | `springboot-verification` | API 변경은 `contract-first`·`api-design-reviewer`, 쿼리는 `postgres-patterns`, 캐시·호출 제한은 `redis-patterns` |
| 계산 검증 | `verification-before-completion` | 차트·분포는 `dataviz` |
| 레퍼런스 | `competitive-platform-analysis` | |
| PM-프론트 검토 | `web-design-guidelines`, `ux-heuristics` | 브라우저 직접 확인 때 `a11y-debugging`·`chrome-devtools`, 상태 화면은 `click-path-audit` |
| PM-백엔드 검토 | `springboot-verification`(결과 확인), `api-design-reviewer` | 보안이 걸리면 `springboot-security` |
| QA 정기 점검 | `playwright-best-practices`, `a11y-debugging` | `web-perf` |
| 배포·운영 | `deployment-patterns`, `docker-patterns` | CI는 `github-actions-templates`, 출시 전 `production-audit`, 모니터링 `observability-and-instrumentation` |

## 형식 점검 (사용자 지시 2026-10-09, 담당 PM이 매 PR 확인)

기준은 [[커밋과 PR 규칙]]의 "제목·라벨·도구 표기 점검" 절이다.

- [ ] 연결 이슈 제목: `[TYPE] 한글`. 9종: FEAT·FIX·REFACTOR·TEST·DOCS·CHORE·RELEASE·HOTFIX·DESIGN
- [ ] 이슈 라벨: 유형별 라벨(feature·bug·chore·documentation·refactor·test·design 등)
- [ ] 이슈와 PR 모두 Projects #1에 연결하고 상태를 갱신(PR 생성 시 검토)
- [ ] PR 제목: `[TYPE] 한글`(이슈와 같은 형식). 병합 커밋: `<type>: 한글 (#PR)`(병합할 때 --subject로 지정)
- [ ] **도구 표기 없음**
  - 대상: 커밋·PR 본문·이슈에 Claude·Codex 참조, "🤖 Generated with Claude Code", `Co-Authored-By` 도구 트레일러
  - 있으면 P1로 판정한다. 지우고 병합한다.
- [ ] 브랜치: `<type>/<issueNumber>-<english-kebab-case>`

형식 위반은 판정의 P1·P2에 함께 적는다. 반복되면 PM 총괄에 알린다.

## 계약 변경 교차 확인 (2026-10-09)

- 백엔드 계약에서 **enum 값 추가**도 프론트 검증기를 깨뜨릴 수 있다. 예: #133에서 crit-speed `sources[].axis`에 CRIT_DAMAGE를 추가해 치적 화면 전체가 오류 위험(P0)이 됐다.
- enum·필수 필드·응답 모양을 바꾸는 백엔드 PR은 PM-백엔드가 PM-프론트 전달함에 알린다. 프론트 계약 갱신 PR이 먼저 병합되거나 동시에 병합되도록 병합 순서를 맞춘다.
- 프론트 검증기는 모르는 enum 값을 만나면 화면 전체 오류 대신 해당 항목만 "알 수 없음"으로 처리하는 방향을 권장한다(프론트팀 후속 과제).

## 계산 영향 PR은 계산 검증팀을 깨운다 (2026-10-09)

계산 검증팀은 필요할 때만 깨우는 팀이라 스스로 오류를 찾지 않는다. 그래서 아래 세 경로로 일을 받는다.

1. **PR 검토 때(담당 PM)**: 계산식·계수·확률표·기본 가정을 새로 넣거나 바꾸는 PR이면, 담당 PM이 `전달함/계산 검증.md`에 검증 요청을 적고 깨운다.
   - 대상: 재련 기대 비용, 치적·공이속, 전투력 모델, 돌파고, 젬 가공, 경매 입찰가, 원정대 영수증, 성장 도구의 새 계산기
   - 새 계산식·계수는 계산 검증 회신(목표 사이트 실측값 대조표: 모든 행이 **일치**이거나 **불일치 + 크라켄이 맞다는 증명**)이 있어야 병합한다. 원인 모를 불일치나 회신 없음은 **P0**다. 막연한 "다를 수 있음" 문구로는 승인하지 않는다([[작업 규칙]] "계산 일치 원칙", 사용자 지시 2026-10-09). 화면 표시만 바뀐 PR은 대상이 아니다.
2. **자동 회귀 검사(CI)**: 계산 검증팀이 만든 교차 값 표본(`src/test/resources/verification/**`)은 백엔드 테스트로 매번 돈다. 계산이 틀어지면 CI가 실패하고, 자동 점검이 담당 PM에게 알린다.
3. **주 1회 패치 확인**: 수요일 정기 점검 뒤 자동 점검이 계산 검증을 깨워, 공식 업데이트 내역에서 계산 규칙에 영향을 주는 변경을 확인한다.
4. 그 밖에 사용자·다른 팀이 "값이 이상하다"고 보고하면 PM 총괄이 계산 검증 전달함에 적고 깨운다.

## 회신 형식

```
[PM 검토 결과] PR #번호 — 승인 | 수정 필요
- P0: …
- P1: …
- P2: …
- 목표 대비 빠진 것: …
```
