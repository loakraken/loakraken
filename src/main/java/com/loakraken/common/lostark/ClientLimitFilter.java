package com.loakraken.common.lostark;

import com.loakraken.common.api.ClientAddresses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/v1/** 요청을 클라이언트별로 센다(#108). 원격 주소는 Tomcat RemoteIpValve가 신뢰 프록시의 X-Forwarded-For로 정한 값이다.
 *
 * <ul>
 *   <li>검색봇 표시({@value #BOT_HEADER}: 1)는 웹이 User-Agent로 판단해 붙인다. 봇 요청은 봇 주소별 BOT 버킷(한도 ≤ GENERAL)을
 *       쓰고, 통계 경로는 그 주소의 STATS도 센다. 공식 호출은 주소별 OFFICIAL과 모든 봇이 함께 쓰는 BOT_OFFICIAL 상한을 함께 센다. 웹은 밖에서
 *       들어온 같은 헤더를 지워야 한다.
 *   <li>그 밖에는 클라이언트별 GENERAL을 세고, 통계·랭킹 경로는 STATS도 센다.
 *   <li>요청을 처리하는 동안 클라이언트 키를 스레드에 두어, 공유 게이트가 공식 호출을 OFFICIAL로 매기게 한다.
 * </ul>
 *
 * 거절은 429 CLIENT_RATE_LIMITED와 Retry-After(지금 창이 끝날 때까지 초)다.
 */
class ClientLimitFilter extends OncePerRequestFilter {
  static final String BOT_HEADER = "X-Loakraken-Bot";
  private final ClientLimiter limiter;

  ClientLimitFilter(ClientLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !limiter.enabled() || !path(request).startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("1".equals(request.getHeader(BOT_HEADER))) {
      // 봇 표시는 User-Agent로 붙어 흉내 낼 수 있다. 그래서 봇 칸은 일반 칸보다 느슨하면 안 된다(PM-백엔드 #151 P1): 주소별 BOT
      // 한도(≤ GENERAL)에 통계 경로는 그 주소의 STATS도 센다. 주소별로 세므로 한 주소가 다른 봇 주소를 막지 못한다.
      var botAddress = request.getRemoteAddr();
      var botClient =
          limiter.botKey(ClientAddresses.normalize(botAddress == null ? "" : botAddress));
      var decision = limiter.check(ClientLimiter.Bucket.BOT, botClient);
      if (decision.allowed() && stats(path(request)))
        decision = limiter.check(ClientLimiter.Bucket.STATS, botClient);
      if (decision.allowed() && rosterRefresh(request))
        decision = limiter.check(ClientLimiter.Bucket.ROSTER_REFRESH, botClient);
      if (!decision.allowed()) {
        reject(response, decision);
        return;
      }
      // 봇의 공식 호출은 그 주소의 OFFICIAL과 모든 봇 합계 BOT_OFFICIAL 상한을 함께 센다(#145).
      run(botClient, request, response, chain);
      return;
    }
    // 제외는 연결 상대(소켓)가 루프백이고 X-Forwarded-For가 아예 없을 때만(같은 호스트의 점검·도구). 신뢰 프록시가 넘긴 주소는 사설·루프백이어도 그
    // 주소로, 프록시가 주소 없이 보낸 요청은 프록시 주소 하나로 센다.
    String address = request.getRemoteAddr();
    String peer = peerAddress(request);
    if (limiter.exemptInternal() && loopback(peer) && peer.equals(address) && !forwarded(request)) {
      limiter.unattributed();
      chain.doFilter(request, response);
      return;
    }
    var client = limiter.clientKey(ClientAddresses.normalize(address == null ? "" : address));
    var decision = limiter.check(ClientLimiter.Bucket.GENERAL, client);
    if (decision.allowed() && stats(path(request)))
      decision = limiter.check(ClientLimiter.Bucket.STATS, client);
    if (decision.allowed() && rosterRefresh(request))
      decision = limiter.check(ClientLimiter.Bucket.ROSTER_REFRESH, client);
    if (!decision.allowed()) {
      reject(response, decision);
      return;
    }
    run(client, request, response, chain);
  }

  private void run(
      String client, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    limiter.enter(client);
    try {
      chain.doFilter(request, response);
    } finally {
      limiter.exit();
    }
  }

  /** 원정대 전투력 불러오기(L19): 공식 호출을 최대 12회 일으키므로 클라이언트당 분당 2회로 따로 센다. */
  static boolean rosterRefresh(HttpServletRequest request) {
    return "POST".equals(request.getMethod())
        && path(request).equals("/api/v1/characters/roster-combat-power/refresh");
  }

  static boolean stats(String path) {
    return path.startsWith("/api/v1/stats/")
        || path.equals("/api/v1/rankings")
        || path.equals("/api/v1/characters/rank");
  }

  /**
   * 연결 상대(소켓) 주소. Tomcat에서는 {@link PeerAddressValve}가 X-Forwarded-For 처리 전에 남긴 값이고, 그 밸브가 없는 환경(서블릿
   * 테스트)은 원격 주소 그대로다.
   */
  static String peerAddress(HttpServletRequest request) {
    return request.getAttribute(PeerAddressValve.ATTRIBUTE) instanceof String peer
        ? peer
        : request.getRemoteAddr();
  }

  /** X-Forwarded-For가 (값과 관계없이) 붙어 왔는지. Tomcat에서는 밸브가 남긴 값, 밸브가 없으면 지금 헤더. */
  static boolean forwarded(HttpServletRequest request) {
    return request.getAttribute(PeerAddressValve.FORWARDED_ATTRIBUTE) instanceof Boolean flag
        ? flag
        : request.getHeader(PeerAddressValve.FORWARDED_HEADER) != null;
  }

  /** 루프백(127.0.0.0/8, ::1)만. 리터럴만 해석하고 DNS는 조회하지 않는다. 비었거나 주소가 아니면 false(센다). */
  static boolean loopback(String address) {
    if (address == null || address.isBlank()) return false;
    try {
      return InetAddress.ofLiteral(address.strip()).isLoopbackAddress();
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static String path(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  private static void reject(HttpServletResponse response, ClientLimiter.Decision decision)
      throws IOException {
    var code = LostArkErrorCode.CLIENT_RATE_LIMITED;
    response.setStatus(code.httpStatus().value());
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"code\":\""
                + code.code()
                + "\",\"message\":\""
                + code.message()
                + "\",\"data\":null}");
  }
}
