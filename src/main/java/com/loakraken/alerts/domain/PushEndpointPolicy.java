package com.loakraken.alerts.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 브라우저 구독 주소(endpoint) 허용 목록(M33). 서버가 이 주소로 요청을 보내므로 알려진 브라우저 푸시 서비스만 받는다. https·기본 포트만, 사용자 정보·IP
 * 리터럴 없이, 호스트는 정확히 일치해야 한다(Windows 푸시만 점 경계 접미사).
 */
public final class PushEndpointPolicy {
  /** 로그에 남길 수 있는 푸시 서비스 종류(주소 원문 대신). */
  public enum Service {
    FCM,
    MOZILLA,
    WINDOWS,
    APPLE
  }

  private static final int MAX_LENGTH = 1024;
  private static final Set<String> EXACT =
      Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com");
  private static final String WINDOWS_SUFFIX = ".notify.windows.com";

  private PushEndpointPolicy() {}

  /** 허용하면 서비스 종류, 아니면 빈 값. */
  public static Optional<Service> check(String endpoint) {
    if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH)
      return Optional.empty();
    URI uri;
    try {
      uri = new URI(endpoint);
    } catch (URISyntaxException exception) {
      return Optional.empty();
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || uri.getHost() == null) return Optional.empty();
    var host = uri.getHost().toLowerCase(Locale.ROOT);
    // IP 리터럴(IPv6 [..] 포함)은 받지 않는다. 허용 호스트는 모두 이름이라 아래 일치 검사에서도 걸러지지만 명시한다.
    if (host.startsWith("[") || host.matches("[0-9.]+")) return Optional.empty();
    if (host.endsWith(".")) return Optional.empty();
    return switch (host) {
      case "fcm.googleapis.com" -> Optional.of(Service.FCM);
      case "updates.push.services.mozilla.com" -> Optional.of(Service.MOZILLA);
      case "web.push.apple.com" -> Optional.of(Service.APPLE);
      default ->
          host.endsWith(WINDOWS_SUFFIX) && host.length() > WINDOWS_SUFFIX.length()
              ? Optional.of(Service.WINDOWS)
              : Optional.empty();
    };
  }

  /** 허용 목록에 있는 정확한 호스트(테스트·문서용). */
  public static Set<String> exactHosts() {
    return EXACT;
  }
}
