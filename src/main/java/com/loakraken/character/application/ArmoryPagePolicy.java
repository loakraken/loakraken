package com.loakraken.character.application;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 공식 전투정보실 페이지 정보(#531) 설정. 공식 Open API와 따로 도는 경로라 한도·클라이언트를 함께 쓰지 않는다.
 *
 * @param enabled 꺼져 있으면(기본) 응답은 200에 값 null이고 공식 페이지를 부르지 않는다
 * @param baseUrl 공식 전투정보실 주소. https이고 호스트는 {@link #HOST}만 허용한다
 * @param product User-Agent의 서비스 이름
 * @param contact User-Agent에 밝히는 연락 주소(서비스 안내 페이지·서비스 전용 메일). 비우면 서비스 이름만
 * @param maxBodyBytes 응답 본문 상한. 넘으면 읽지 않고 실패로 센다
 * @param cacheTtl 뽑은 값 캐시 시간
 * @param failureTtl 실패 결과 캐시 시간(같은 캐릭터 재시도 폭주 방지)
 * @param minInterval 인스턴스 전체 공식 페이지 요청 사이 최소 간격
 * @param maxWait 간격을 지키려고 기다릴 수 있는 최대 시간. 넘으면 요청하지 않고 값 null
 * @param backoffInitial 공식 쪽 실패 뒤 첫 쉬는 시간(실패마다 두 배)
 * @param backoffMax 쉬는 시간 상한이자 연속 실패로 멈춘 뒤 다시 한 번 시험하기까지의 시간
 * @param stopAfterFailures 공식 쪽 연속 실패가 이 수에 이르면 멈춘다
 * @param stopAfterParseFailures 페이지 구조를 못 읽은 일이 연속 이 수에 이르면 멈춘다(재시작 전까지)
 */
@ConfigurationProperties("armory-page")
public record ArmoryPagePolicy(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("https://lostark.game.onstove.com") URI baseUrl,
    @DefaultValue("LOAKraken") String product,
    @DefaultValue("") String contact,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout,
    @DefaultValue("3000000") int maxBodyBytes,
    @DefaultValue("6h") Duration cacheTtl,
    @DefaultValue("10m") Duration failureTtl,
    @DefaultValue("1s") Duration minInterval,
    @DefaultValue("2s") Duration maxWait,
    @DefaultValue("1m") Duration backoffInitial,
    @DefaultValue("30m") Duration backoffMax,
    @DefaultValue("5") int stopAfterFailures,
    @DefaultValue("3") int stopAfterParseFailures) {
  public static final String HOST = "lostark.game.onstove.com";

  public ArmoryPagePolicy {
    if (baseUrl == null || !"https".equals(baseUrl.getScheme()) || !HOST.equals(baseUrl.getHost()))
      throw new IllegalArgumentException("전투정보실 주소는 https://" + HOST + " 만 허용합니다.");
    if (product == null || product.isBlank())
      throw new IllegalArgumentException("User-Agent 서비스 이름이 비었습니다.");
    contact = contact == null ? "" : contact.strip();
    if (maxBodyBytes < 1) throw new IllegalArgumentException("본문 상한은 1 이상이어야 합니다.");
    if (stopAfterFailures < 1 || stopAfterParseFailures < 1)
      throw new IllegalArgumentException("멈춤 기준은 1 이상이어야 합니다.");
    for (var duration :
        new Duration[] {
          connectTimeout, readTimeout, cacheTtl, failureTtl, minInterval, backoffInitial, backoffMax
        })
      if (duration == null || duration.isNegative() || duration.isZero())
        throw new IllegalArgumentException("전투정보실 시간 설정은 양수여야 합니다.");
    if (maxWait == null || maxWait.isNegative())
      throw new IllegalArgumentException("기다림 상한은 0 이상이어야 합니다.");
  }

  /** 서비스임을 밝히는 User-Agent(브라우저 값으로 꾸미지 않는다). */
  public String userAgent() {
    return contact.isEmpty() ? product : product + " (+" + contact + ")";
  }
}
