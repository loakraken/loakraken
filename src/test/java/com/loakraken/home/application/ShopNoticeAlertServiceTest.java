package com.loakraken.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.home.domain.NewsFeed;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 상점 공지 알림(#636): 처음 실행은 기준만, 새 상점 공지는 한 번만, Slack 실패면 다음 실행에 다시. */
class ShopNoticeAlertServiceTest {
  private final HomeSourcePort source = mock(HomeSourcePort.class);
  private final LostArkRequestGate gate = mock(LostArkRequestGate.class);
  private final MemoryStore store = new MemoryStore();
  private final List<String> sent = new ArrayList<>();
  private boolean failSend;
  private final ShopNoticeNotifier notifier =
      text -> {
        if (failSend) throw new IllegalStateException("down");
        sent.add(text);
      };
  private final ShopNoticeAlertService service =
      new ShopNoticeAlertService(
          source,
          store,
          notifier,
          gate,
          new ShopNoticePolicy(true, List.of("상점", "패키지", "로아샵", "상품"), 40, ""));

  {
    when(gate.withReserve(anyInt(), any()))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
  }

  @Test
  void 처음_실행은_지금_상점_공지를_기준으로만_저장하고_알리지_않는다() {
    when(source.fetchNotices()).thenReturn(List.of(shop("A"), notice("일반 안내", "N1")));
    var result = service.run();
    assertThat(result.baseline()).isTrue();
    assertThat(result.alerted()).isZero();
    assertThat(sent).isEmpty();
    assertThat(store.seen()).containsExactly(url("A"));
    // 공식 호출은 공지만 1회, 백그라운드 몫(40)을 남기고.
    verify(gate, times(1)).withReserve(org.mockito.ArgumentMatchers.eq(40), any());
    verify(source, times(1)).fetchNotices();
  }

  @Test
  void 새_상점_공지는_한_번만_알리고_분류가_아니어도_제목_말로_고른다() {
    when(source.fetchNotices()).thenReturn(List.of(shop("A")));
    service.run(); // 기준
    when(source.fetchNotices())
        .thenReturn(
            List.of(
                shop("A"),
                shop("B"),
                notice("10월 23일(목) 신규 패키지 판매 안내", "P1"),
                notice("서버 점검 안내", "M1")));
    var result = service.run();
    assertThat(result.alerted()).isEqualTo(2);
    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst())
        .startsWith("loa-shop update needed — 새 상점 공지 2건")
        .contains("<" + url("B") + "|상점 B>")
        .contains("<" + url("P1") + "|10월 23일(목) 신규 패키지 판매 안내>")
        .doesNotContain("서버 점검");
    // 같은 공지는 다시 알리지 않는다.
    var again = service.run();
    assertThat(again.alerted()).isZero();
    assertThat(sent).hasSize(1);
  }

  @Test
  void Slack_전송이_실패하면_기억하지_않아_다음_실행에_다시_알린다() {
    when(source.fetchNotices()).thenReturn(List.of());
    service.run(); // 기준(상점 공지 없음)
    when(source.fetchNotices()).thenReturn(List.of(shop("C")));
    failSend = true;
    assertThatThrownBy(service::run).isInstanceOf(IllegalStateException.class);
    assertThat(store.seen()).doesNotContain(url("C"));
    failSend = false;
    assertThat(service.run().alerted()).isEqualTo(1);
    assertThat(sent).hasSize(1);
  }

  @Test
  void 제목의_꺾쇠와_앰퍼샌드는_Slack_문법에서_바꿔_쓴다() {
    var text =
        ShopNoticeAlertService.message(
            List.of(
                new NewsFeed.Notice(
                    "<상점> A&B", NewsFeed.Category.SHOP, LocalDateTime.now(), url("X"))));
    assertThat(text).contains("|&lt;상점&gt; A&amp;B>");
  }

  private static NewsFeed.Notice shop(String id) {
    return new NewsFeed.Notice(
        "상점 " + id, NewsFeed.Category.SHOP, LocalDateTime.of(2026, 10, 22, 10, 0), url(id));
  }

  private static NewsFeed.Notice notice(String title, String id) {
    return new NewsFeed.Notice(
        title, NewsFeed.Category.NOTICE, LocalDateTime.of(2026, 10, 22, 10, 0), url(id));
  }

  private static String url(String id) {
    return "https://lostark.game.onstove.com/News/Notice/Views/" + id;
  }

  private static final class MemoryStore implements ShopNoticeStore {
    private final Set<String> seen = new HashSet<>();
    private boolean initialized;

    @Override
    public boolean initialized() {
      return initialized;
    }

    @Override
    public Set<String> seen() {
      return Set.copyOf(seen);
    }

    @Override
    public void remember(Collection<String> urls) {
      seen.addAll(urls);
      initialized = true;
    }
  }
}
