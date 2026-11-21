package com.loakraken.common.lostark;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 검색봇 표시 요청의 주소가 진짜 크롤러인지 확인한다(공식 안내의 역방향 DNS + 정방향 확인). 봇 표시는 웹이 User-Agent로 붙이므로 흉내 낼 수 있고, 흉내 낸
 * 주소 몇 개가 모든 봇이 함께 쓰는 공식 호출 상한을 다 쓰면 진짜 크롤러가 막힌다. 웹이 뚫렸다고 보고 백엔드가 주소로 직접 확인한다.
 *
 * <ul>
 *   <li>역방향 이름이 설정한 크롤러 도메인(점 경계)으로 끝나고, 그 이름의 정방향 주소에 원래 주소가 있을 때만 확인됨.
 *   <li>결과는 주소마다 남긴다(확인됨 24시간, 아님 1시간 기본, 둘은 따로 상한). 같은 주소의 동시 확인은 하나로 합친다.
 *   <li>확인을 띄운 요청만 짧게(기본 300ms) 기다린다. 넘거나, 진행 중인 확인에 합류했거나, 확인 자리가 차면 이번 요청은 확인되지 않은 것(사람 칸)으로 본다.
 *       실패는 모두 사람 칸이다.
 * </ul>
 */
public class CrawlerVerifier {
  /** 주소 → 이름, 이름 → 주소 조회. 운영은 JVM 이름 해석(시스템 리졸버), 테스트는 가짜. */
  interface Resolver {
    /** 역방향 이름. 이름이 없으면 null(또는 주소 문자열 그대로). */
    String reverse(InetAddress address) throws Exception;

    List<InetAddress> forward(String host) throws Exception;
  }

  static final Resolver SYSTEM =
      new Resolver() {
        @Override
        public String reverse(InetAddress address) throws UnknownHostException {
          return InetAddress.getByAddress(address.getAddress()).getHostName();
        }

        @Override
        public List<InetAddress> forward(String host) throws UnknownHostException {
          return List.of(InetAddress.getAllByName(host));
        }
      };

  private final CrawlerVerifyProperties properties;
  private final Resolver resolver;
  private final Executor executor;
  private final Clock clock;
  private final MeterRegistry registry;
  // 확인된 주소와 아닌 주소를 따로 남긴다: 흉내 낸 주소가 아무리 많아도 확인된 크롤러 결과 자리를 밀어내지 못한다.
  private final ConcurrentHashMap<String, Instant> verifiedUntil = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Instant> unverifiedUntil = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending =
      new ConcurrentHashMap<>();

  CrawlerVerifier(
      CrawlerVerifyProperties properties,
      Resolver resolver,
      Executor executor,
      Clock clock,
      MeterRegistry registry) {
    this.properties = properties;
    this.resolver = resolver;
    this.executor = executor;
    this.clock = clock;
    this.registry = registry;
  }

  /** 운영용: 확인 스레드 2개, 대기 자리 maxPending개(넘으면 거절 = 사람 칸). */
  static CrawlerVerifier system(
      CrawlerVerifyProperties properties, Clock clock, MeterRegistry registry) {
    var threads = new AtomicInteger();
    var executor =
        new ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(properties.maxPending()),
            runnable -> {
              var thread = new Thread(runnable, "crawler-verify-" + threads.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
    return new CrawlerVerifier(properties, SYSTEM, executor, clock, registry);
  }

  /** 이 주소(리터럴만, DNS 이름은 아님)가 확인된 크롤러인가. 모르거나 실패하면 false. */
  boolean verified(String address) {
    InetAddress literal;
    try {
      literal = InetAddress.ofLiteral(address == null ? "" : address.strip());
    } catch (IllegalArgumentException exception) {
      return count("unverified", false);
    }
    var key = literal.getHostAddress();
    var now = clock.instant();
    if (fresh(verifiedUntil, key, now)) return count("verified", true);
    if (fresh(unverifiedUntil, key, now)) return count("unverified", false);
    // 확인을 띄운 요청만 짧게 기다린다. 이미 진행 중인 확인에 합류한 요청은 기다리지 않고 사람 칸이다(이름 서버를 느리게 만든 주소가 요청
    // 스레드를 붙잡지 못하게: 동시에 기다리는 요청은 확인 자리 수를 넘지 않는다).
    var created = new boolean[1];
    CompletableFuture<Boolean> lookup;
    try {
      lookup =
          pending.computeIfAbsent(
              key,
              ignored -> {
                created[0] = true;
                return start(key, literal);
              });
    } catch (RejectedExecutionException exception) {
      return count("busy", false);
    }
    // 끝난 확인은 대기 표에서 뺀다(computeIfAbsent 밖에서: 같은 스레드에서 바로 끝나도 재귀 갱신이 되지 않게).
    var started = lookup;
    lookup.whenComplete((verified, error) -> pending.remove(key, started));
    if (!created[0] && !lookup.isDone()) return count("pending", false);
    try {
      boolean verified = lookup.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
      return count(verified ? "verified" : "unverified", verified);
    } catch (TimeoutException exception) {
      return count("timeout", false);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return count("timeout", false);
    } catch (ExecutionException exception) {
      return count("unverified", false);
    }
  }

  private CompletableFuture<Boolean> start(String key, InetAddress literal) {
    var future = new CompletableFuture<Boolean>();
    executor.execute(
        () -> {
          boolean verified = false;
          var result = "unverified";
          long begin = System.nanoTime();
          try {
            verified = check(literal);
            if (verified) result = "verified";
          } catch (Exception ignored) {
            // 조회 실패는 확인되지 않은 것.
            result = "error";
          }
          // 이름 조회(외부 의존) 시간: 확인 지연·자리 참(timeout·busy)이 늘 때 이름 서버가 느려졌는지 본다.
          if (registry != null)
            registry
                .timer("loakraken.client.limit.crawler_verify.lookup", "result", result)
                .record(System.nanoTime() - begin, TimeUnit.NANOSECONDS);
          remember(key, verified);
          future.complete(verified);
        });
    return future;
  }

  private boolean check(InetAddress literal) throws Exception {
    var name = resolver.reverse(literal);
    if (name == null) return false;
    var host = name.strip().toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    if (!crawlerHost(host)) return false;
    for (var forward : resolver.forward(host)) if (forward.equals(literal)) return true;
    return false;
  }

  private boolean crawlerHost(String host) {
    for (var suffix : properties.suffixes()) if (host.endsWith("." + suffix)) return true;
    return false;
  }

  private static boolean fresh(ConcurrentHashMap<String, Instant> until, String key, Instant now) {
    var expires = until.get(key);
    return expires != null && expires.isAfter(now);
  }

  private void remember(String key, boolean verified) {
    var now = clock.instant();
    var until = verified ? verifiedUntil : unverifiedUntil;
    (verified ? unverifiedUntil : verifiedUntil).remove(key);
    if (until.size() >= properties.maxEntries())
      until.values().removeIf(expires -> !expires.isAfter(now));
    if (until.size() >= properties.maxEntries() && !until.containsKey(key)) return;
    until.put(key, now.plus(verified ? properties.verifiedTtl() : properties.unverifiedTtl()));
  }

  private boolean count(String outcome, boolean verified) {
    if (registry != null)
      registry.counter("loakraken.client.limit.crawler_verify", "outcome", outcome).increment();
    return verified;
  }
}
