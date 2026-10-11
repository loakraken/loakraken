# 계산 검증

크라켄이 보여 줄 계산값이 공식 수치와 맞는지 확인한 기록이다. 계산 로직은 `src/main`에 두고, 이곳과 `src/test/resources/verification/`에는 검증 방법·표본·기대값만 둔다.

| 모델 | 문서 | 표본·기대값 | 상태 |
|---|---|---|---|
| 전투력 | [combat-power.md](combat-power.md) | `verification/combat-power/` | 절대값 모델 미검증(표본 밖 RMS 딜러 4.67%·1740+ 3.89%, 기준 2%). **보석 변화량은 목표 사이트 A와 일치**(5명 30건 ±0.01, 2026-10-10). **재련 모델 확정**(B 역산, 목표 사이트 A 10건 ±0.0013, 2026-10-11) |
| 치명타·공이속 | [crit-speed.md](crit-speed.md) | `verification/crit-speed/` | 치적 일치(언새늘 77.21%), 치명타 피해 6명 일치(v3). 갈증 리퍼 치적 규칙 차이(피냄새) 열림 |
| 아크 그리드 젬 가공 | [arkgrid-gem.md](arkgrid-gem.md) | `verification/arkgrid-gem/` | 성공 확률 재현(로아또 7/7, 반올림 범위). 기대 비용은 근사(−9.8~+0.3%), 미검증 |
| 재련 기대 시도 수 | [refining.md](refining.md) | `verification/refining/` | 목표 사이트 B 최적 루트 3사례 일치(기대 시도 차 0.0043 이내, 최대 시도 같음). 골드 비용은 대상 아님 |
| 돌파고(어빌리티 스톤 세공) | [ability-stone.md](ability-stone.md) | `verification/ability-stone/` | 확률 규칙 일치(공식 결과표 4개 최대 0.00093%p, 목표 사이트 C 2사례) |
| 경매 입찰가 | [auction.md](auction.md) | `verification/auction/` | 목표 사이트 C·목표 사이트 A 교차 일치(선점 반올림 1G 차). 수수료 5%·10% 재입찰은 공식 문구 미확인(추정) |
| 스킬 쿨타임(보석 반영) | [skill-cooldown.md](skill-cooldown.md) | `verification/skill/` | 공식 원문 쿨타임 × (1 − 보석 쿨감) 확정(5명 51개 스킬, 2026-10-12). 아이덴티티·노드·신속 감소와 1타 피해는 제외 |
| 스펙업 비용 근거 | [spec-up-cost.md](spec-up-cost.md) | `verification/spec-up/` | 각인서 레벨당 5권 확정(공식 가이드 + 목표 사이트 비용 구조), 스톤 보너스(합 ≥ 5 → 1.5%) 공식 문구 확인. 무기 품질 업그레이드 비용은 공식 확률 미공개로 UNSUPPORTED |
| 로아샵 효율 | [loa-shop.md](loa-shop.md) | `verification/loa-shop/` | 구성품 골드 환산·공식 크리스탈 경로 이득 식 확정(목표 사이트 화면 값 원 단위 재현, 2026-10-12). 시세 없는 상자는 공식 확률 × 시세 |
| 장신구 기본 스탯 범위 % | [accessory-stat-range.md](accessory-stat-range.md) | `verification/accessory/` | 공식 아이템 사전 연마 단계별 범위로 식 확정, 언새늘 5개 목표 사이트와 소수 둘째 자리까지 일치(L11, 2026-10-12) |
| 지옥 보상 효율 | [hell-reward.md](hell-reward.md) | `verification/hell-reward/` | 낙원 시즌 4 공식 규칙·강하 횟수·보상표 88칸 확정(인벤 + 사이트 20칸 일치, L42, 2026-10-12). 층→단계·특수 효과 미확정 |
| 기여도 계산기 기준 피해량 | [raid-contribution.md](raid-contribution.md) | `verification/raid-contribution/` | 43관문 하한·상한(1인분 × 0.9 ~ 1.2), CONFIRMED 37 · HOLD 6(두 출처 차이 공지로 설명 안 됨). 관문별 최신 출처(공식 공지 체력·보호막 변경 대조), 내 위치 기대값(L41, 2026-10-12) |

## 규칙

- 기대값마다 출처를 적는다: 공식 표시값(`OFFICIAL_DISPLAY`), 목표 사이트 계산값(`TARGET_SITE`), 추정(`ESTIMATE`).
- 가설마다 잔차표(표본별 오차, 평균, 최대)를 남긴다. 기준 이하가 될 때까지 "검증됨"으로 쓰지 않는다. 미검증 값은 공개 응답에 넣지 않는다.
- "검증됨" 판정은 표본 밖 잔차로 한다. 계수를 적합한 표본의 잔차(표본 내)만으로는 판정하지 않고, k-겹 교차검증이나 수집 시점이 다른 보류 표본의 잔차가 기준 이하여야 한다.
- 여러 가설을 교차검증 점수로 비교해 골랐다면 선택 편향이 남는다. 그 경우 최종 "검증됨"은 모델을 고정한 뒤 **새로 모은 보류 표본**(수집 시점이 다른 표본)으로만 판정한다.
- 공식 API 원문은 커밋하지 않는다. 모델에 쓰는 특성만 남기고, 기준 캐릭터 외 이름은 익명화한다.
- 표본 수집 시 공식 API 호출 수를 문서에 적는다.
