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
}
