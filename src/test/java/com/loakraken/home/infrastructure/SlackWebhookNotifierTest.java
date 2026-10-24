package com.loakraken.home.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Slack 웹훅 주소는 비밀 파일에서 읽고, Slack 주소만 쓰며, 예외 문장에 주소를 넣지 않는다. */
class SlackWebhookNotifierTest {
  @TempDir Path dir;

  @Test
  void 비밀_파일의_Slack_주소를_읽는다() throws Exception {
    var file = dir.resolve("hook");
    Files.writeString(file, "https://hooks.slack.com/services/T0/B0/xyz\n");
    assertThat(SlackWebhookNotifier.webhook(file.toString()))
        .isEqualTo("https://hooks.slack.com/services/T0/B0/xyz");
  }

  @Test
  void 파일이_없거나_Slack_주소가_아니면_주소를_드러내지_않고_거절한다() throws Exception {
    assertThatThrownBy(() -> SlackWebhookNotifier.webhook(""))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> SlackWebhookNotifier.webhook(dir.resolve("none").toString()))
        .isInstanceOf(IllegalStateException.class);
    var other = dir.resolve("other");
    Files.writeString(other, "https://attacker.example/collect");
    assertThatThrownBy(() -> SlackWebhookNotifier.webhook(other.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("attacker.example");
  }

  @Test
  void 앞부분을_흉내_낸_주소와_빈_파일과_줄바꿈은_주소를_드러내지_않고_거절한다() throws Exception {
    // 코드 리뷰 10-25: 앞부분 검사를 비켜 가는 경우(다른 호스트·사용자 정보·http·빈 파일·중간 공백·줄바꿈)
    var values =
        java.util.List.of(
            "https://hooks.slack.com.evil.example/services/T0/B0/x",
            "https://hooks.slack.com@evil.example/services/T0/B0/x",
            "https://user@hooks.slack.com/services/T0/B0/x",
            "http://hooks.slack.com/services/T0/B0/x",
            "https://hooks.slack.com:8443/services/T0/B0/x",
            "",
            "   ",
            "https://hooks.slack.com/services/T0/B0/x
https://evil.example/",
            "https://hooks.slack.com/services/T0/B0/xhttps://evil.example/",
            "https://hooks.slack.com/services/T0/B0/x y",
            "https://hooks.slack.com/services/T0/B0/x	y");
    for (var value : values) {
      var file = dir.resolve("hook-" + Integer.toHexString(value.hashCode()));
      Files.writeString(file, value);
      assertThatThrownBy(() -> SlackWebhookNotifier.uri(SlackWebhookNotifier.webhook(file.toString())))
          .as(value)
          .isInstanceOf(IllegalStateException.class)
          .hasNoCause()
          .hasMessageNotContaining("evil.example")
          .hasMessageNotContaining("T0/B0");
    }
    // 정상 주소는 그대로
    var ok = dir.resolve("ok");
    Files.writeString(ok, "https://hooks.slack.com/services/T0/B0/xyz");
    assertThat(SlackWebhookNotifier.uri(SlackWebhookNotifier.webhook(ok.toString())).getHost())
        .isEqualTo("hooks.slack.com");
  }

  @Test
  void Slack_주소로_시작해도_형식이_틀리면_문장과_원인에_주소를_남기지_않는다() throws Exception {
    var bad = dir.resolve("bad");
    Files.writeString(bad, "https://hooks.slack.com/services/T0/B0/se cret");
    var url = SlackWebhookNotifier.webhook(bad.toString());
    assertThatThrownBy(() -> SlackWebhookNotifier.uri(url))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .hasMessageNotContaining("hooks.slack.com")
        .hasMessageNotContaining("se cret");
  }
}
