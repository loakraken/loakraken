package com.loakraken.home.application;

import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.home.domain.NewsFeed;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 상점 공지 알림(#636). 공식 공지 목록을 1회 읽어(백그라운드 몫을 남김) 상점 분류이거나 제목에 상점·패키지 말이 있는 공지 가운데 처음 보는 것만 Slack에 한 번
 * 알린다. 공지 내용(패키지 구성)은 읽지 않는다. 처음 켠 실행은 지금 공지를 기준으로만 저장하고 알리지 않는다. Slack 전송이 실패하면 기억하지 않아 다음 실행에 다시
 * 알린다.
 */
@Service
public class ShopNoticeAlertService {
  static final String HEADLINE = "loa-shop update needed";

  private final HomeSourcePort source;
  private final ShopNoticeStore store;
  private final ShopNoticeNotifier notifier;
  private final LostArkRequestGate gate;
  private final ShopNoticePolicy policy;

  public ShopNoticeAlertService(
      HomeSourcePort source,
      ShopNoticeStore store,
      ShopNoticeNotifier notifier,
      LostArkRequestGate gate,
      ShopNoticePolicy policy) {
    this.source = source;
    this.store = store;
    this.notifier = notifier;
    this.gate = gate;
    this.policy = policy;
  }

  /** baseline: 처음 실행이라 기준만 저장했는지, alerted: 이번에 알린 공지 수. */
  public record Result(int shopNotices, int alerted, boolean baseline) {}

  public Result run() {
    var notices = gate.withReserve(policy.reserve(), source::fetchNotices);
    var shop = notices.stream().filter(this::shop).filter(notice -> notice.url() != null).toList();
    if (!store.initialized()) {
      store.remember(shop.stream().map(NewsFeed.Notice::url).toList());
      return new Result(shop.size(), 0, true);
    }
    var seen = store.seen();
    var fresh = shop.stream().filter(notice -> !seen.contains(notice.url())).toList();
    if (fresh.isEmpty()) return new Result(shop.size(), 0, false);
    notifier.send(message(fresh));
    store.remember(fresh.stream().map(NewsFeed.Notice::url).toList());
    return new Result(shop.size(), fresh.size(), false);
  }

  boolean shop(NewsFeed.Notice notice) {
    if (notice.category() == NewsFeed.Category.SHOP) return true;
    var title = notice.title();
    return title != null && policy.keywords().stream().anyMatch(title::contains);
  }

  /** Slack 글: 머리 한 줄 + 공지마다 링크 한 줄(Slack 링크 문법, 제목의 &·<·>는 바꿔 씀). */
  static String message(List<NewsFeed.Notice> notices) {
    var text = new StringBuilder(HEADLINE).append(" — 새 상점 공지 ").append(notices.size()).append("건");
    for (var notice : notices)
      text.append("\n• <")
          .append(notice.url())
          .append('|')
          .append(escape(notice.title()))
          .append('>');
    return text.toString();
  }

  static String escape(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
