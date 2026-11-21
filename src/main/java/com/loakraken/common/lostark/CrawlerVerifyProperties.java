package com.loakraken.common.lostark;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 검색봇 표시 요청의 주소 확인(역방향 DNS + 정방향 확인). 확인된 주소만 봇 칸을 쓰고, 나머지는 사람 클라이언트 칸으로 센다.
 *
 * <p>기본 접미사는 공식 안내 문서가 진짜 크롤러의 역방향 이름으로 든 것만이다(2026-11-22 확인): Google
 * developers.google.com/search/docs/crawling-indexing/verifying-googlebot(googlebot.com·google.com),
 * Bing blogs.bing.com/webmaster/August-2012/How-to-Verify-that-Bingbot-is-Bingbot(search.msn.com),
 * Apple support.apple.com/en-us/119829(applebot.apple.com). googleusercontent.com은 클라우드 고객 서버도 그
 * 이름을 받으므로 넣지 않는다.
 *
 * @param suffixes 역방향 이름이 끝나야 하는 크롤러 도메인(점 경계로 비교, 소문자). 점이 없는 값(최상위 도메인만)은 받지 않는다
 * @param timeout 요청 안에서 확인을 기다리는 최대 시간. 넘으면 이번 요청은 사람 칸, 확인은 뒤에서 마저 해 결과를 남긴다
 * @param verifiedTtl 확인된 주소 결과를 쓰는 시간
 * @param unverifiedTtl 확인되지 않은 주소 결과를 쓰는 시간
 * @param maxEntries 결과를 남기는 주소 수 상한(넘으면 만료된 것부터 비우고, 그래도 차면 새 결과를 남기지 않는다)
 * @param maxPending 동시에 기다리는 확인 수 상한(넘는 요청은 확인 없이 사람 칸)
 */
@ConfigurationProperties("lostark.crawler-verify")
public record CrawlerVerifyProperties(
    @DefaultValue({"googlebot.com", "google.com", "search.msn.com", "applebot.apple.com"})
        List<String> suffixes,
    @DefaultValue("300ms") Duration timeout,
    @DefaultValue("24h") Duration verifiedTtl,
    @DefaultValue("1h") Duration unverifiedTtl,
    @DefaultValue("10000") int maxEntries,
    @DefaultValue("32") int maxPending) {
  public CrawlerVerifyProperties {
    suffixes =
        suffixes.stream()
            .map(suffix -> suffix.strip().toLowerCase(Locale.ROOT))
            .filter(suffix -> !suffix.isEmpty())
            .toList();
    for (var suffix : suffixes)
      if (suffix.startsWith(".") || suffix.endsWith(".") || !suffix.contains("."))
        throw new IllegalArgumentException("크롤러 도메인 접미사 형식이 아닙니다: " + suffix);
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("크롤러 확인 대기 시간은 0보다 커야 합니다.");
    if (maxEntries < 1 || maxPending < 1)
      throw new IllegalArgumentException("크롤러 확인 상한은 1 이상이어야 합니다.");
  }
}
