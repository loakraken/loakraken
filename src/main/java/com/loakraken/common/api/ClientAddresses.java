package com.loakraken.common.api;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

/**
 * 클라이언트 주소를 사람 단위 키로 정규화한다. 인기 검색 중복 제거(F-10)와 유입 제한(#108)이 같이 쓴다.
 *
 * <ul>
 *   <li>IPv4는 주소 그대로 쓴다.
 *   <li>IPv4-mapped IPv6(::ffff:a.b.c.d)는 IPv4로 바꾼다.
 *   <li>IPv6는 /64 접두사로 줄인다. 보통 한 사용자가 /64 하나를 받으므로, 주소를 바꿔 가며 우회하는 것을 막는다.
 * </ul>
 *
 * DNS 조회는 하지 않는다(리터럴만 해석). 해석할 수 없는 값은 앞뒤 공백만 지운 원문을 돌려준다.
 */
public final class ClientAddresses {
  private ClientAddresses() {}

  public static String normalize(String address) {
    if (address == null) return null;
    String value = address.strip();
    if (value.startsWith("[") && value.endsWith("]"))
      value = value.substring(1, value.length() - 1);
    int zone = value.indexOf('%');
    if (zone >= 0) value = value.substring(0, zone);
    if (value.isEmpty()) return value;
    InetAddress parsed;
    try {
      parsed = InetAddress.ofLiteral(value);
    } catch (IllegalArgumentException exception) {
      return value;
    }
    if (parsed instanceof Inet4Address) return parsed.getHostAddress();
    if (parsed instanceof Inet6Address) {
      var bytes = parsed.getAddress();
      if (ipv4Mapped(bytes))
        return (bytes[12] & 0xff)
            + "."
            + (bytes[13] & 0xff)
            + "."
            + (bytes[14] & 0xff)
            + "."
            + (bytes[15] & 0xff);
      var hex = HexFormat.of();
      var prefix = new StringBuilder();
      for (int i = 0; i < 8; i += 2) {
        if (i > 0) prefix.append(':');
        prefix.append(hex.formatHex(bytes, i, i + 2));
      }
      return prefix.append("::/64").toString();
    }
    return parsed.getHostAddress();
  }

  /** ::ffff:a.b.c.d. ofLiteral이 Inet6Address로 줄 때가 있어 직접 본다. */
  private static boolean ipv4Mapped(byte[] bytes) {
    if (bytes.length != 16) return false;
    for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
    return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
  }
}
