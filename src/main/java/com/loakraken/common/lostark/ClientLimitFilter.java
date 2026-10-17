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
    String address = request.getRemoteAddr();
    if (limiter.exemptInternal() && internal(address)) {
      limiter.unattributed();
      chain.doFilter(request, response);
      return;
    }
    var client = limiter.clientKey(ClientAddresses.normalize(address));
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

  /** 루프백·사설망·링크 로컬·고유 로컬(fc00::/7). 리터럴만 해석하고 DNS는 조회하지 않는다. */
  static boolean internal(String address) {
    if (address == null || address.isBlank()) return true;
    InetAddress parsed;
    try {
      parsed = InetAddress.ofLiteral(address.strip());
    } catch (IllegalArgumentException exception) {
      return false;
    }
    if (parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || parsed.isLinkLocalAddress())
      return true;
    var bytes = parsed.getAddress();
    return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
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
