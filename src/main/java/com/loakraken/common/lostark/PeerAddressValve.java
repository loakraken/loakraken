package com.loakraken.common.lostark;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * 연결한 상대(소켓) 주소를 요청 속성 {@value #ATTRIBUTE}에 남긴다. 신뢰 프록시의 X-Forwarded-For로 원격 주소를 바꾸는
 * RemoteIpValve보다 먼저 돌아야 하므로 엔진 밸브 맨 앞에 둔다({@link LostArkGateConfiguration}). 클라이언트 한도의 제외 판정은 바뀐
 * 원격 주소가 아니라 이 값으로 한다.
 */
class PeerAddressValve extends ValveBase {
  static final String ATTRIBUTE = "com.loakraken.peerAddress";

  /** X-Forwarded-For가 (값과 관계없이) 붙어 왔는지. RemoteIpValve가 헤더를 지우기 전에 남긴다. */
  static final String FORWARDED_ATTRIBUTE = "com.loakraken.forwarded";

  static final String FORWARDED_HEADER = "X-Forwarded-For";

  PeerAddressValve() {
    super(true);
  }

  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {
    request.setAttribute(ATTRIBUTE, request.getRemoteAddr());
    request.setAttribute(FORWARDED_ATTRIBUTE, request.getHeader(FORWARDED_HEADER) != null);
    getNext().invoke(request, response);
  }
}
