package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * 검색봇 표시 주소 확인(#458 P2 ①): 역방향 이름이 공식 크롤러 도메인(점 경계)으로 끝나고 정방향 주소에 원래 주소가 있어야 확인. 실패·시간 초과·자리 없음은 모두
 * 확인되지 않음(사람 칸).
 */
class CrawlerVerifierTest {
  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final FakeResolver resolver = new FakeResolver();

  @Test
  void 역방향_이름이_크롤러_도메인이고_정방향이_같은_주소면_확인하고_결과를_남긴다() {
    resolver.name("66.249.66.1", "crawl-66-249-66-1.googlebot.com");
    var verifier = verifier(Runnable::run);
    assertThat(verifier.verified("66.249.66.1")).isTrue();
    assertThat(verifier.verified("66.249.66.1")).isTrue();
    assertThat(resolver.reverseCalls).isEqualTo(1);
    // 끝의 점·대문자도 같은 이름이다.
    resolver.name("157.55.39.1", "MSNBOT-157-55-39-1.SEARCH.MSN.COM.");
    assertThat(verifier.verified("157.55.39.1")).isTrue();
    assertThat(outcome("verified")).isEqualTo(3);
    // 이름 조회는 결과별 시간으로 남는다(결과 남김 적중은 조회가 아님).
    assertThat(lookups("verified")).isEqualTo(2);
    resolver.failing = true;
    assertThat(verifier.verified("198.51.100.20")).isFalse();
    assertThat(lookups("error")).isEqualTo(1);
  }

  @Test
  void 도메인은_점_경계로만_맞추고_정방향이_다르면_확인하지_않는다() {
    var verifier = verifier(Runnable::run);
    resolver.name("203.0.113.1", "crawl.evilgooglebot.com");
    resolver.name("203.0.113.2", "crawl.googlebot.com.evil.example");
    resolver.name("203.0.113.3", "googlebot.com");
    assertThat(verifier.verified("203.0.113.1")).isFalse();
    assertThat(verifier.verified("203.0.113.2")).isFalse();
    assertThat(verifier.verified("203.0.113.3")).isFalse();
    // 흉내 낸 역방향 이름(주소 주인이 마음대로 정함): 정방향은 다른 주소다.
    resolver.reverse.put("203.0.113.4", "crawl-66-249-66-2.googlebot.com");
    resolver.forward.put("crawl-66-249-66-2.googlebot.com", List.of("66.249.66.2"));
    assertThat(verifier.verified("203.0.113.4")).isFalse();
  }

  @Test
  void 이름이_없거나_조회가_실패하거나_주소_리터럴이_아니면_확인하지_않는다() {
    var verifier = verifier(Runnable::run);
    assertThat(verifier.verified("198.51.100.7")).isFalse();
    resolver.failing = true;
    assertThat(verifier.verified("198.51.100.8")).isFalse();
    // DNS 이름·빈 값은 조회하지 않는다.
    var calls = resolver.reverseCalls;
    assertThat(verifier.verified("crawl-66-249-66-1.googlebot.com")).isFalse();
    assertThat(verifier.verified("")).isFalse();
    assertThat(verifier.verified(null)).isFalse();
    assertThat(resolver.reverseCalls).isEqualTo(calls);
  }

  @Test
  void 확인되지_않은_결과도_남겼다가_만료되면_다시_조회한다() {
    var verifier = verifier(Runnable::run);
    assertThat(verifier.verified("198.51.100.9")).isFalse();
    assertThat(verifier.verified("198.51.100.9")).isFalse();
    assertThat(resolver.reverseCalls).isEqualTo(1);
    clock.now = clock.now.plus(Duration.ofHours(1)).plusSeconds(1);
    resolver.name("198.51.100.9", "crawl-198-51-100-9.googlebot.com");
    assertThat(verifier.verified("198.51.100.9")).isTrue();
    assertThat(resolver.reverseCalls).isEqualTo(2);
  }

  @Test
  void 확인이_시간_안에_끝나지_않으면_이번_요청은_확인되지_않음이고_끝난_결과는_다음_요청이_쓴다() {
    resolver.name("66.249.66.5", "crawl-66-249-66-5.googlebot.com");
    var queued = new ArrayList<Runnable>();
    var verifier = verifier(queued::add);
    long start = System.nanoTime();
    assertThat(verifier.verified("66.249.66.5")).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    // 같은 주소의 다음 요청은 새 조회를 띄우지 않고, 진행 중인 확인을 기다리지도 않는다(바로 사람 칸).
    long joined = System.nanoTime();
    assertThat(verifier.verified("66.249.66.5")).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - joined)).isLessThan(Duration.ofMillis(50));
    assertThat(queued).hasSize(1);
    assertThat(outcome("timeout")).isEqualTo(1);
    assertThat(outcome("pending")).isEqualTo(1);
    queued.getFirst().run();
    assertThat(verifier.verified("66.249.66.5")).isTrue();
    assertThat(resolver.reverseCalls).isEqualTo(1);
  }

  @Test
  void 확인_자리가_차면_조회하지_않고_확인되지_않음이다() {
    Executor full =
        task -> {
          throw new RejectedExecutionException("full");
        };
    var verifier = verifier(full);
    assertThat(verifier.verified("66.249.66.6")).isFalse();
    assertThat(outcome("busy")).isEqualTo(1);
    assertThat(resolver.reverseCalls).isZero();
  }

  @Test
  void 결과_수_상한을_넘으면_새_결과를_남기지_않고_확인된_결과는_따로_센다() {
    var properties =
        new CrawlerVerifyProperties(
            List.of("googlebot.com"),
            Duration.ofMillis(100),
            Duration.ofHours(24),
            Duration.ofHours(1),
            2,
            4);
    var verifier = new CrawlerVerifier(properties, resolver, Runnable::run, clock, registry);
    verifier.verified("198.51.100.1");
    verifier.verified("198.51.100.2");
    verifier.verified("198.51.100.3");
    verifier.verified("198.51.100.3");
    // 세 번째 주소는 남지 않아 다시 조회한다(앞 두 주소는 남음).
    assertThat(resolver.reverseCalls).isEqualTo(4);
    verifier.verified("198.51.100.1");
    assertThat(resolver.reverseCalls).isEqualTo(4);
    // 확인되지 않은 주소가 자리를 다 채워도 확인된 크롤러 결과는 따로 남는다.
    resolver.name("66.249.66.7", "crawl-66-249-66-7.googlebot.com");
    assertThat(verifier.verified("66.249.66.7")).isTrue();
    assertThat(verifier.verified("66.249.66.7")).isTrue();
    assertThat(resolver.reverseCalls).isEqualTo(5);
  }

  @Test
  void 기본_도메인은_공식_문서의_크롤러_이름이고_최상위_도메인만은_받지_않는다() {
    var defaults =
        new CrawlerVerifyProperties(
            List.of("googlebot.com", "google.com", "search.msn.com", "applebot.apple.com"),
            Duration.ofMillis(300),
            Duration.ofHours(24),
            Duration.ofHours(1),
            10000,
            32);
    assertThat(defaults.suffixes()).doesNotContain("googleusercontent.com");
    assertThat(
            new CrawlerVerifyProperties(
                    List.of(" Search.MSN.com "),
                    Duration.ofMillis(1),
                    Duration.ofHours(1),
                    Duration.ofHours(1),
                    1,
                    1)
                .suffixes())
        .containsExactly("search.msn.com");
    for (var bad : List.of("com", ".google.com", "google.com."))
      assertThatThrownBy(
              () ->
                  new CrawlerVerifyProperties(
                      List.of(bad),
                      Duration.ofMillis(300),
                      Duration.ofHours(24),
                      Duration.ofHours(1),
                      10,
                      1))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
  }

  private CrawlerVerifier verifier(Executor executor) {
    return new CrawlerVerifier(
        new CrawlerVerifyProperties(
            List.of("googlebot.com", "google.com", "search.msn.com", "applebot.apple.com"),
            Duration.ofMillis(100),
            Duration.ofHours(24),
            Duration.ofHours(1),
            100,
            8),
        resolver,
        executor,
        clock,
        registry);
  }

  private long lookups(String result) {
    var timer =
        registry.find("loakraken.client.limit.crawler_verify.lookup").tag("result", result).timer();
    return timer == null ? 0 : timer.count();
  }

  private double outcome(String outcome) {
    var counter =
        registry.find("loakraken.client.limit.crawler_verify").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  /** 주소 → 이름, 이름 → 주소 표. 이름을 정하면 정방향도 그 주소 하나로 둔다. */
  private static final class FakeResolver implements CrawlerVerifier.Resolver {
    final Map<String, String> reverse = new HashMap<>();
    final Map<String, List<String>> forward = new HashMap<>();
    int reverseCalls;
    boolean failing;

    void name(String address, String host) {
      reverse.put(address, host);
      var key = host.toLowerCase(java.util.Locale.ROOT);
      if (key.endsWith(".")) key = key.substring(0, key.length() - 1);
      forward.put(key, List.of(address));
    }

    @Override
    public String reverse(InetAddress address) throws Exception {
      reverseCalls++;
      if (failing) throw new java.net.UnknownHostException("down");
      return reverse.get(address.getHostAddress());
    }

    @Override
    public List<InetAddress> forward(String host) {
      return forward.getOrDefault(host, List.of()).stream().map(InetAddress::ofLiteral).toList();
    }
  }

  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-22T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
