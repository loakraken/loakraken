# 장비 요약 테이블 (#146, equipment-summary-v1)

PM 총괄 결정(2026-10-09): 공식 armory 원문은 저장하지 않는다. armory를 받을 때마다(사용자 검색·통계 수집기) 이미 해석한 도메인에서 계산 검증에 필요한 특성만 뽑아 `character_equipment_summary`에 남긴다. 설계: 볼트 `10 백엔드/04 설계/장비 요약 테이블 설계.md`.

## 테이블 (V11)

| 열 | 내용 |
|---|---|
| summary_id | BIGINT IDENTITY PK |
| character_id | `characters` FK, **ON DELETE CASCADE**(캐릭터 단위 삭제·B9 1년 미관측 정리에 포함) |
| observed_at / last_confirmed_at | 이 특성을 처음 본 시각 / 같은 특성을 마지막으로 확인한 시각 |
| schema_version | `equipment-summary-v1` |
| combat_power, item_level, character_level, class_name, ark_passive_title | 같은 armory의 공식 값 |
| features | JSONB(키 정렬 직렬화). 아래 필드 |
| features_hash | SHA-256(스키마 버전 + features + 공식 전투력) |

- 변경 감지: 최신 행과 해시가 같으면 `last_confirmed_at`만 바꾼다. 특성이나 공식 전투력이 하나라도 바뀌면 새 행이다. 그래서 "관측 시점마다 (특성, 공식 전투력) 쌍"이 구간 행으로 남는다.
- 공식 응답 순서만 바뀐 관측은 같은 해시다. 배열은 고정 순서(부위 순서, 보석 슬롯, 각인·노드·스킬 이름)로 두고, 같은 부위(귀걸이·반지)는 연마 문장으로 정렬한다.
- 쓰기: 카드·포지션 기록과 같은 비동기 단일 작업자(대기열 1000)에서 한다. 응답 경로에 동기 쓰기가 없고 공식 호출은 늘지 않는다. 캐릭터 행은 `FOR UPDATE`로 잠가 여러 인스턴스가 같은 캐릭터를 동시에 써도 행이 겹치지 않는다.
- 지표: `loakraken.character.equipment_summary.writes{outcome=created|confirmed|skipped|failed}`

## features

| 필드 | 출처 |
|---|---|
| attack{total, base} | 공식 스탯 "공격력" 값과 Tooltip "기본 공격력은 N" |
| stats{crit, spec, swift, domination, endurance, expertise} | 공식 스탯 |
| arkPassive{points, karmaRank, karmaLevel, nodes[{tree, tier, name, level}]} | 포인트, 설명 "6랭크 21레벨", 노드 "깨달음 1티어 버스트 강화 Lv.1" |
| engravings[{name, grade, level, stone}] | 아크 패시브 각인. stone은 어빌리티 스톤 레벨(없으면 0) |
| gems[{kind, level, mode}], gemBaseAttackPct | 보석 이름(겁화·작열·광휘·멸화·홍염), 보석 효과의 "재사용 대기시간"→COOLDOWN·"피해"→DAMAGE, "기본 공격력 총합 : x%" |
| weapon{quality, addDamagePct, weaponAttack, refine} | 무기 효과 "추가 피해 +x%"·"무기 공격력 +N" |
| armor[{slot, refine, quality, itemLevel}] | 투구·상의·하의·장갑·어깨 |
| accessories[{slot, quality, polishLines[]}] | 연마 효과 "이름 값" |
| braceletLines[] | 팔찌 효과 문장 |
| stoneLevelBonusBaseAttackPct | 어빌리티 스톤 "[레벨 보너스] 기본 공격력 +x%" |
| arkGrid{effects{이름: %}, cores[{kind, position, grade, point, options[]}]} | 그리드 효과, 코어 이름 "질서의 해 코어", 현재 포인트로 달성한 구간 문장 |
| cards{set, pieces, awakening, elementPct} | 카드 세트 효과 이름 "… 6세트 (30각성합계)", "성속성 피해 +x%" 합 |
| orbParadisePower | 보주 "최대 낙원력 : N" |
| skills[{name, attackType}] | 레벨 2 이상 스킬, #133 공격 타입 |

- 못 읽은 값은 null이다(추정 금지). 엘릭서·초월·상급 재련은 지금 도메인에 해석이 없어 v1에 넣지 않는다. 계산 검증팀이 필요하면 v2에서 더한다.
- 코어 구간 효과는 문장 그대로 둔다. 수치화는 계산 검증 규칙이 확정되면 v2에서 한다.

## 표본 대조 (2026-10-09, 8081 검증용 DB)

공식 원문은 커밋하지 않는다. 언새늘 등 5명을 검색해 만든 요약을 `src/test/resources/verification/combat-power/samples-2026-10-08.json`과 비교했다(공식 호출 최대 10회: armory·원정대 각 5회). 표본에서 이름이 남은 캐릭터는 언새늘뿐이라 필드 비교는 언새늘로 한다.

| 필드 | 결과 |
|---|---|
| stats, points, karmaLevel, engravings, gems(종류·레벨·모드 11개), weapon, 연마 합(표본 키 6개), 팔찌, 그리드 효과, 코어(종류·등급·포인트), 카드 속성 피해, 보주 낙원력 | 같음 |
| attack 197857 → 198702, baseAttack 187350 → 188163 | 10-08 이후 실제로 바뀜(공식 전투력도 4035.27 → 4052.77) |

행 크기는 5명 평균 약 5.5KB였다(노드 19개·스킬 8개·코어 구간 문장 포함). 변경이 있을 때만 행이 생기므로, 1만 명이 주 1회 장비를 바꾸면 약 2.9GB/년이다. 실제 증가율은 운영 지표로 본다.

## 계산 검증 내보내기

캐릭터 이름이 들어간 덤프는 **서버 밖으로 옮기지 않는다.** 서버 안에서 익명화(sNNN)한 결과만 꺼낸다. 기준 캐릭터(언새늘)만 이름을 둔다.

```sql
-- 캐릭터별 최신 요약을 아이템 레벨·전투력 순으로 익명화한다. psql로 서버 안에서 실행해 결과 JSON만 꺼낸다.
WITH latest AS (
  SELECT DISTINCT ON (s.character_id) s.*, c.character_name
  FROM character_equipment_summary s JOIN characters c USING (character_id)
  WHERE NOT EXISTS (
    SELECT 1 FROM public_exclusion e
    WHERE e.character_name = c.character_name
      AND (e.server_name IS NULL OR e.server_name = c.server_name))
  ORDER BY s.character_id, s.observed_at DESC
)
SELECT json_build_object(
  'id', CASE WHEN character_name = '언새늘' THEN character_name
             ELSE 's' || lpad(row_number() OVER (ORDER BY item_level DESC, combat_power DESC)::text, 3, '0') END,
  'className', class_name, 'arkPassiveTitle', ark_passive_title, 'itemLevel', item_level,
  'characterLevel', character_level, 'combatPower', combat_power, 'observedAt', observed_at,
  'features', features)
FROM latest;
```

- `public_exclusion`(처리정지 요청, 서버 생략 시 모든 서버) 캐릭터는 내보내지 않는다.
