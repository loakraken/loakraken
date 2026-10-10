package com.loakraken.character.application;

import java.time.Instant;
import java.time.LocalDate;

/** B9 보유 기간 정리(#141·#142). 한 번에 limit 행까지 지우고 지운 수를 돌려준다. */
public interface CharacterRetentionPort {
  /**
   * 마지막 관측(검색 last_synced_at·수집 profile_refreshed_at·원정대 확인 roster_scanned_at 중 가장 늦은 값)이 cutoff보다
   * 앞선 캐릭터를 지운다. 기록(스냅샷·관측 이력·칭호·카드·장비 요약)은 FK CASCADE로 함께 지워진다. public_exclusion은 지우지 않는다.
   */
  int deleteUnobservedCharacters(Instant cutoff, int limit);

  /** KST 날짜가 cutoff보다 앞선 조회수 일별 행을 지운다. */
  int deleteViewsBefore(LocalDate cutoff, int limit);
}
