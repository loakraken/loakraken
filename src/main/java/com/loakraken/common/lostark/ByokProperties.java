package com.loakraken.common.lostark;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 사용자 공식 API 키(BYOK). 기본은 꺼짐이고, 꺼져 있으면 {@value UserKeyFilter#HEADER} 헤더를 읽지도 않는다.
 *
 * <ul>
 *   <li>trustedPeer: 그 헤더를 받아 줄 연결 상대(소켓) 주소 정규식. 기본은 X-Forwarded-For를 믿는 프록시와 같은 값(web 한 곳)이고, 비어
 *       있으면 어떤 상대도 믿지 않는다.
 *   <li>perKeyPerMinute: 키 하나의 분당 공식 호출(공식 한도 100보다 낮게 — 사용자의 다른 도구 몫).
 *   <li>perKeyConcurrency: 키 하나의 동시 공식 호출.
 * </ul>
 */
@ConfigurationProperties("lostark.byok")
public record ByokProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("") String trustedPeer,
    @DefaultValue("90") int perKeyPerMinute,
    @DefaultValue("2") int perKeyConcurrency) {
  public ByokProperties {
    if (perKeyPerMinute < 1 || perKeyPerMinute > 100 || perKeyConcurrency < 1)
      throw new IllegalArgumentException("사용자 키 한도는 1~100회/분, 동시 1 이상이어야 합니다.");
    trustedPeer = trustedPeer == null ? "" : trustedPeer.strip();
  }

  /** 연결 상대가 믿는 web인지. 정규식이 비었으면 아무도 믿지 않는다. */
  boolean trusted(String peer) {
    if (trustedPeer.isEmpty() || peer == null) return false;
    return Pattern.matches(trustedPeer, peer);
  }
}
