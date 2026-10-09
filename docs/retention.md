# 보유 기간 정리 (B9, #141·#142)

PM 총괄 결정(2026-10-09)을 구현한다. 처리방침의 데이터 목록은 볼트 `30 PM/백엔드/개인정보 처리 데이터 목록 (B9).md`이다.

| 데이터 | 보유 기간 | 정리 방식 |
|---|---|---|
| 캐릭터(`characters`)와 그 기록: 스냅샷·관측 이력·칭호·카드·장비 요약 | 마지막 관측 후 1년 동안 다시 관측되지 않으면 삭제 | 캐릭터 행을 지우면 FK `ON DELETE CASCADE`로 함께 지워진다(V12에서 스냅샷·관측 이력·칭호를 CASCADE로 맞춤) |
| 조회수 일별(`character_view_daily`) | 1년(KST 날짜 기준 365일) | 날짜가 지난 행 삭제 |
| 인기 검색 시간별(`character_popular_hourly`) | 기존 정리 작업(#112, 1시간마다) | 변경 없음 |
| 공개 제외(`public_exclusion`) | 해제 요청 때까지 | 지우지 않는다. 캐릭터 기록이 지워져도 남아, 다시 관측되면 계속 제외한다 |

## 마지막 관측

`GREATEST(last_synced_at, profile_refreshed_at, roster_scanned_at)`이다.

- `last_synced_at`: 사용자 검색으로 공식 전투정보를 받은 시각
- `profile_refreshed_at`: 통계 수집기가 프로필을 갱신한 시각
- `roster_scanned_at`: 원정대 조회에서 이 캐릭터가 보인 시각(원정대 목록에 있으면 아직 있는 캐릭터다)
- `profile_checked_at`은 "없는 캐릭터" 확인도 포함하므로 관측으로 보지 않는다.

## 실행

- 매일 04:30 KST(`character.retention.cron`). 여러 인스턴스 중 리더 임대 `character-retention`(30분)을 얻은 한 곳만 돈다.
- 한 배치 500행, 한 번에 최대 40배치(2만 행). 남은 행은 다음 날 이어서 지운다. 트랜잭션은 배치마다 30초 제한이다.
- 끄기: `character.retention.enabled=false`
- 지표: `loakraken.retention.deleted{target=characters|view_daily}`, `loakraken.background.last_success{job=retention}`
- 로그: `entryPoint=retention`, 지운 수만 남긴다(캐릭터명 없음).

## 수집기와 공개 제외

통계 수집기는 공개 제외 캐릭터(서버 생략 시 모든 서버)를 프로필 갱신·원정대 조회 대상으로 고르지 않는다. 다른 캐릭터의 원정대에서 보여도 새로 기록하거나 갱신하지 않는다(#141 범위의 확인 항목).
