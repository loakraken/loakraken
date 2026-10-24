package com.loakraken.home.infrastructure;

import com.loakraken.home.application.ShopNoticeNotifier;
import com.loakraken.home.application.ShopNoticePolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Slack 수신 웹훅으로 글 한 개를 보낸다. 주소는 보낼 때마다 비밀 파일에서 읽고(설정·로그·예외 문장에 두지 않음), {@code
 * https://hooks.slack.com/}로 시작하는 주소만 쓴다(파일이 바뀌어도 다른 곳으로 보내지 않게).
 */
@Component
class SlackWebhookNotifier implements ShopNoticeNotifier {
  static final String SLACK_PREFIX = "https://hooks.slack.com/";

  private final ShopNoticePolicy policy;
  private final RestClient client;

  SlackWebhookNotifier(ShopNoticePolicy policy) {
    this.policy = policy;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public void send(String text) {
    var uri = uri(webhook(policy.slackWebhookFile()));
    try {
      client
          .post()
          .uri(uri)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("text", text))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException exception) {
      throw new IllegalStateException(
          "Slack 알림을 보내지 못했습니다. 예외 유형=" + exception.getClass().getSimpleName());
    }
  }

  /** 주소 형식이 틀리면 URI 예외 문장에 주소가 그대로 들어가므로, 문장·원인 없이 새 예외로 바꾼다. */
  static URI uri(String url) {
    try {
      return URI.create(url);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Slack 웹훅 파일 내용이 올바른 주소가 아닙니다.");
    }
  }

  /** 비밀 파일의 첫 줄(앞뒤 공백 제외). 경로가 비었거나 읽지 못하거나 Slack 주소가 아니면 예외(주소는 문장에 넣지 않음). */
  static String webhook(String file) {
    if (file == null || file.isBlank()) throw new IllegalStateException("Slack 웹훅 파일이 설정되지 않았습니다.");
    String url;
    try {
      url = Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
    } catch (IOException exception) {
      throw new IllegalStateException("Slack 웹훅 파일을 읽지 못했습니다.");
    }
    if (!url.startsWith(SLACK_PREFIX) || url.contains("\n"))
      throw new IllegalStateException("Slack 웹훅 파일 내용이 Slack 주소가 아닙니다.");
    return url;
  }
}
