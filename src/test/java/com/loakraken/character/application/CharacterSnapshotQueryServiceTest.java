package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class CharacterSnapshotQueryServiceTest {
  @Test
  void 저장소_장애와_트랜잭션_연결_장애는_공개용_503으로_변환한다() {
    var history = mock(CharacterSnapshotHistoryPort.class);
    var query = new SnapshotHistoryQuery("모험가", "루페온", 20, null);
    var service = new CharacterSnapshotQueryService(history);
    for (var failure :
        new RuntimeException[] {
          new DataAccessResourceFailureException("internal connection detail"),
          new CannotCreateTransactionException("internal transaction detail")
        }) {
      doThrow(failure).when(history).find(query);
      assertThatThrownBy(() -> service.history(query))
          .isInstanceOf(CharacterException.class)
          .hasMessage("캐릭터 관측 이력을 조회할 수 없습니다.");
    }
  }
}
