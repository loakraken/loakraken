package com.loakraken.character.infrastructure;

import com.loakraken.character.application.ArmoryPageFetchException;
import com.loakraken.character.application.ArmoryPageFetchException.Kind;
import com.loakraken.character.application.ArmoryPagePolicy;
import com.loakraken.character.application.ArmoryPagePort;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공식 전투정보실 캐릭터 페이지 요청(#531). 공식 Open API 클라이언트·키·한도와 완전히 따로 둔다: 쿠키 없음, 리다이렉트를 따라가지 않음, 호스트
 * 고정({@link ArmoryPagePolicy#HOST}), 서비스임을 밝히는 User-Agent, 본문 상한. 본문은 메모리에서 값만 뽑고 버린다.
 */
@Component
class OfficialArmoryPageClient implements ArmoryPagePort {
  /** 128자 16진수(앞뒤가 16진수가 아님). */
  private static final Pattern CODE =
      Pattern.compile("(?<![0-9A-Fa-f])[0-9A-Fa-f]{128}(?![0-9A-Fa-f])");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final RestClient client;
  private final int maxBodyBytes;

  @Autowired
  OfficialArmoryPageClient(ArmoryPagePolicy policy) {
    this(policy, RestClient.builder().requestFactory(requestFactory(policy)));
  }

  OfficialArmoryPageClient(ArmoryPagePolicy policy, RestClient.Builder builder) {
    this.client =
        builder
            .baseUrl(policy.baseUrl().toString())
            .defaultHeader(HttpHeaders.USER_AGENT, policy.userAgent())
            .defaultHeader(HttpHeaders.ACCEPT, "text/html")
            .build();
    this.maxBodyBytes = policy.maxBodyBytes();
  }

  private static JdkClientHttpRequestFactory requestFactory(ArmoryPagePolicy policy) {
    var http =
        HttpClient.newBuilder()
            .connectTimeout(policy.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(policy.readTimeout());
    return factory;
  }

  @Override
  public Page fetch(String characterName) {
    String html;
    try {
      html =
          client
              .get()
              .uri("/Profile/Character/{name}", characterName)
              .exchange(
                  (request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 401 || status == 403)
                      throw new ArmoryPageFetchException(Kind.ACCESS_DENIED, status);
                    if (status == 404) throw new ArmoryPageFetchException(Kind.NOT_FOUND, status);
                    if (status != 200) throw new ArmoryPageFetchException(Kind.HTTP_ERROR, status);
                    return read(response.getBody());
                  });
    } catch (ResourceAccessException exception) {
      throw new ArmoryPageFetchException(Kind.TIMEOUT, null);
    }
    return ArmoryPageParser.parse(html);
  }

  /**
   * 페이지의 스킬 코드 버튼과 같은 요청({@code POST /Profile/SkillRecommend}, 폼 본문 memberNo·worldNo·pcId). 응답 JSON의 code가 0이
   * 아니면 거절로 보고 ACCESS_DENIED(우회하지 않음 — 쿠키·로그인·토큰을 흉내 내지 않는다). content 안의 128자 16진수만 꺼낸다.
   */
  @Override
  public String skillCode(Identity identity) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("memberNo", identity.memberNo());
    form.add("worldNo", identity.worldNo());
    form.add("pcId", identity.pcId());
    String body;
    try {
      body =
          client
              .post()
              .uri("/Profile/SkillRecommend")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .headers(headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)))
              .body(form)
              .exchange(
                  (request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 401 || status == 403)
                      throw new ArmoryPageFetchException(Kind.ACCESS_DENIED, status);
                    if (status != 200) throw new ArmoryPageFetchException(Kind.HTTP_ERROR, status);
                    return read(response.getBody());
                  });
    } catch (ResourceAccessException exception) {
      throw new ArmoryPageFetchException(Kind.TIMEOUT, null);
    }
    return code(body);
  }

  static String code(String body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JacksonException exception) {
      // JSON이 아니면(로그인 안내 HTML 등) 구조가 바뀐 것으로 센다.
      throw new ArmoryPageFetchException(Kind.PARSE_ERROR, 200);
    }
    if (!root.path("code").isNumber())
      throw new ArmoryPageFetchException(Kind.PARSE_ERROR, 200);
    if (root.path("code").asInt() != 0)
      throw new ArmoryPageFetchException(Kind.ACCESS_DENIED, 200);
    var matcher = CODE.matcher(root.path("content").asString(""));
    if (!matcher.find()) throw new ArmoryPageFetchException(Kind.PARSE_ERROR, 200);
    return matcher.group().toUpperCase(java.util.Locale.ROOT);
  }

  private String read(InputStream body) throws IOException {
    var bytes = body.readNBytes(maxBodyBytes + 1);
    if (bytes.length > maxBodyBytes) throw new ArmoryPageFetchException(Kind.TOO_LARGE, 200);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
