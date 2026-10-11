package com.loakraken.character.application;

import com.loakraken.character.application.CharacterPopularPort.PopularItem;
import com.loakraken.character.application.CharacterPopularPort.PopularResult;
import com.loakraken.common.api.ClientAddresses;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 인기 검색 캐릭터(F-10, popular-v1). 캐릭터 검색 성공마다 고유 조회를 센다. 조회수(F-8, 반복 포함)와 분리한다.
 *
 * <ul>
 *   <li>같은 클라이언트·같은 캐릭터는 KST 하루 1회만 센다. 키는 HMAC(일별 salt, IP + 캐릭터)이다. salt는 메모리에만 두고 날마다 바꾸며 저장하거나
 *       로그에 남기지 않는다. 원본 IP는 어디에도 저장하지 않는다.
 *   <li>고유 조회는 KST가 아닌 UTC 시간 버킷에 모은다. 24시간은 현재 시각이 속한 버킷을 포함한 최근 24개 버킷이다.
 *   <li>기록은 응답 경로 밖에서 단일 작업자가 비동기로 한다. 대기열이 차면 그 조회는 세지 않는다.
 *   <li>인스턴스마다 salt가 달라, 여러 인스턴스를 오가는 같은 클라이언트는 하루 2회 이상 셀 수 있다(단일 인스턴스 전제).
 * </ul>
 */
@Service
public class CharacterPopularService {
  public static final String RULE_VERSION = "popular-v1";
  public static final int MAX_LIMIT = 50;
  static final int QUEUE_CAPACITY = 1000;
  private static final Logger log = LoggerFactory.getLogger(CharacterPopularService.class);
  private final CharacterPopularPort popular;
  private final CharacterStopList stopList;
  private final CharacterPopularPolicy policy;
  private final CharacterMetrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  // 이중 확인 잠금 밖에서 읽으므로 volatile이다.
  private volatile DailySalt salt;
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          Thread.ofVirtual().name("character-popular-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public CharacterPopularService(
      CharacterPopularPort popular,
      CharacterPopularPolicy policy,
      CharacterMetrics metrics,
      Clock clock,
      CharacterStopList stopList) {
    this.popular = popular;
    this.stopList = stopList;
    this.policy = policy;
    this.metrics = metrics;
    this.clock = clock;
  }

  public enum Period {
    LAST_24_HOURS("24h", 24),
    LAST_7_DAYS("7d", 24 * 7);

    private final String key;
    private final int hours;

    Period(String key, int hours) {
      this.key = key;
      this.hours = hours;
    }

    public String key() {
      return key;
    }

    public static Period of(String key) {
      for (var period : values()) if (period.key.equals(key)) return period;
      throw new IllegalArgumentException("period는 24h 또는 7d입니다.");
    }
  }

  /** 검색 성공 뒤 호출한다. clientAddress는 신뢰 프록시를 거친 원격 주소다. 실패는 검색을 막지 않는다. */
  public void record(String characterName, String serverName, String clientAddress) {
    if (!policy.enabled()
        || characterName == null
        || characterName.isBlank()
        || serverName == null
        || clientAddress == null
        || clientAddress.isBlank()) return;
    var now = clock.instant();
    var day = LocalDate.ofInstant(now, CharacterViewService.KST);
    String key = clientKey(day, clientAddress, serverName, characterName);
    try {
      writer.execute(() -> write(day, key, characterName, serverName, now));
    } catch (RejectedExecutionException exception) {
      metrics.popular(false);
    }
  }

  private void write(
      LocalDate day, String key, String characterName, String serverName, Instant now) {
    try {
      // 처리정지 목록의 캐릭터는 세지 않는다(#351).
      if (!stopList.recordable(characterName, serverName)) return;
      if (popular.markSeen(day, key))
        popular.increment(characterName, serverName, now.truncatedTo(ChronoUnit.HOURS));
      metrics.popular(true);
    } catch (DataAccessException | TransactionException exception) {
      metrics.popular(false);
      log.warn("인기 검색 기록에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  public PopularResult popular(Period period, int limit) {
    if (!policy.enabled())
      throw new CharacterException(CharacterErrorCode.CHARACTER_POPULAR_DISABLED);
    String cacheKey = period.key() + ":" + limit;
    try {
      var cached = popular.findCached(cacheKey);
      if (cached.isPresent()) return cached.get();
    } catch (DataAccessException exception) {
      // 캐시 장애는 집계로 대신한다.
    }
    var now = clock.instant();
    var since = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(period.hours - 1L));
    PopularResult result;
    try {
      long total = popular.totalUniqueViews(since);
      boolean ready = total >= policy.minSample();
      var items = new ArrayList<PopularItem>();
      if (ready) {
        int rank = 1;
        for (var row : popular.top(since, minUniqueViews(period), limit))
          items.add(
              new PopularItem(
                  rank++, row.characterName(), row.serverName(), row.className(), row.itemLevel()));
      }
      result =
          new PopularResult(
              period.key(), now, ready ? "READY" : "COLLECTING", minUniqueViews(period), items);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_POPULAR_UNAVAILABLE);
    }
    try {
      popular.saveCached(cacheKey, result);
    } catch (DataAccessException exception) {
      log.warn("인기 검색 캐시를 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
    return result;
  }

  /** 보관 기간이 지난 시간 버킷을 지운다. */
  public int purge() {
    return popular.purgeBefore(clock.instant().minus(policy.retention()));
  }

  int minUniqueViews(Period period) {
    return period == Period.LAST_7_DAYS
        ? policy.minUniqueViews().week()
        : policy.minUniqueViews().day();
  }

  /** 주소는 사람 단위로 정규화한다(IPv6 /64, IPv4-mapped → IPv4). */
  String clientKey(LocalDate day, String clientAddress, String serverName, String characterName) {
    String client = ClientAddresses.normalize(clientAddress);
    var current = salt;
    if (current == null || !current.day().equals(day)) {
      synchronized (this) {
        current = salt;
        if (current == null || !current.day().equals(day)) {
          var bytes = new byte[32];
          random.nextBytes(bytes);
          current = new DailySalt(day, bytes);
          salt = current;
        }
      }
    }
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(current.bytes(), "HmacSHA256"));
      var digest =
          mac.doFinal(
              (client + "\n" + serverName + "\n" + characterName).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 16);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    writer.shutdown();
    writer.awaitTermination(5, TimeUnit.SECONDS);
  }

  private record DailySalt(LocalDate day, byte[] bytes) {}
}
