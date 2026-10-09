package com.loakraken.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientAddressesTest {
  @Test
  void IPv4는_그대로_쓴다() {
    assertThat(ClientAddresses.normalize(" 203.0.113.7 ")).isEqualTo("203.0.113.7");
    assertThat(ClientAddresses.normalize("203.0.113.8"))
        .isNotEqualTo(ClientAddresses.normalize("203.0.113.7"));
  }

  @Test
  void 같은_64의_IPv6는_같은_키이고_다른_64는_다르다() {
    var a = ClientAddresses.normalize("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd");
    var b = ClientAddresses.normalize("2001:0db8:1234:5678::1");
    var c = ClientAddresses.normalize("2001:db8:1234:5679::1");
    assertThat(a).isEqualTo(b).isEqualTo("2001:0db8:1234:5678::/64");
    assertThat(c).isNotEqualTo(a);
    // 대괄호·zone 표기도 같은 키다.
    assertThat(ClientAddresses.normalize("[2001:db8:1234:5678::2]")).isEqualTo(a);
    assertThat(ClientAddresses.normalize("fe80::1%eth0")).isEqualTo("fe80:0000:0000:0000::/64");
  }

  @Test
  void IPv4_mapped는_IPv4와_같은_키다() {
    assertThat(ClientAddresses.normalize("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
  }

  @Test
  void 해석할_수_없는_값은_DNS_조회_없이_원문을_돌려준다() {
    assertThat(ClientAddresses.normalize("unknown")).isEqualTo("unknown");
    assertThat(ClientAddresses.normalize(null)).isNull();
  }
}
