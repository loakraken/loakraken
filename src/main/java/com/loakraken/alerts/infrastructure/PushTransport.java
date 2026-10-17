package com.loakraken.alerts.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 푸시 서비스로 POST 한 번(M33). 상태 코드만 돌려준다. 테스트는 가짜 전송으로 바꾼다. */
public interface PushTransport {
  int post(URI endpoint, Map<String, String> headers, byte[] body)
      throws IOException, InterruptedException;

  /** java.net.http 전송. 리다이렉트를 따르지 않고(허용 목록 밖으로 가지 않게), 연결 5초·응답 10초 타임아웃, 응답 본문은 읽지 않고 버린다. */
  final class Http implements PushTransport {
    private final HttpClient client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public int post(URI endpoint, Map<String, String> headers, byte[] body)
        throws IOException, InterruptedException {
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofByteArray(body));
      headers.forEach(request::header);
      return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }
}
