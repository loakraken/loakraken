package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.loakraken.character.application.ArmoryPageFetchException;
import com.loakraken.character.application.ArmoryPageFetchException.Kind;
import com.loakraken.character.application.ArmoryPagePolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 공식 전투정보실 페이지 클라이언트(#531·#532): 실제 요청 없이 MockRestServiceServer로. */
class OfficialArmoryPageClientTest {
  private static final String URL = "https://lostark.game.onstove.com/Profile/Character/";

  private static ArmoryPagePolicy policy(int maxBodyBytes, String contact) {
    return new ArmoryPagePolicy(
        true,
        URI.create("https://lostark.game.onstove.com"),
        "LOAKraken",
        contact,
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        maxBodyBytes,
        Duration.ofHours(6),
        Duration.ofMinutes(10),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofMinutes(1),
        Duration.ofMinutes(30),
        5,
        3,
        new ArmoryPagePolicy.SkillCode(true));
  }

  private static String fixture() throws IOException {
    return new ClassPathResource("armory-page/profile-trimmed.html")
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void 생활_스킬을_공식_순서로_뽑고_서비스임을_밝힌다() throws Exception {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo(URL + "%ED%85%8C%EC%8A%A4%ED%8A%B8%EC%BA%90%EB%A6%AD"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("User-Agent", "LOAKraken (+https://example.com/contact)"))
        .andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
    var page =
        new OfficialArmoryPageClient(policy(3_000_000, "https://example.com/contact"), builder)
            .fetch("테스트캐릭");
    assertThat(page.lifeSkills())
        .extracting(skill -> skill.name() + "=" + skill.level())
        .containsExactly("식물 채집=14", "벌목=23", "채광=7", "수렵=14", "낚시=23", "고고학=70");
    server.verify();
  }

  @Test
  void 페이지_식별_값을_읽되_문자열로_드러내지_않는다() throws Exception {
    var page = ArmoryPageParser.parse(fixture());
    assertThat(page.identity()).isNotNull();
    assertThat(page.identity().memberNo()).startsWith("TESTMEMBER");
    assertThat(page.identity().worldNo()).startsWith("TESTWORLD");
    assertThat(page.identity().pcId()).startsWith("TESTPCID");
    // 로그·예외 메시지로 새지 않게 toString은 값을 숨긴다(#532).
    assertThat(page.toString()).doesNotContain("TESTMEMBER", "TESTWORLD", "TESTPCID");
    assertThat(page.identity().toString()).doesNotContain("TESTMEMBER");
    // 스크립트 변수가 없으면 식별 값만 null(생활 스킬은 그대로).
    var noScript =
        ArmoryPageParser.parse(
            "<ul class='profile-skill-life__list'><li><strong>낚시</strong><span>Lv.3</span></li></ul>");
    assertThat(noScript.identity()).isNull();
    assertThat(noScript.lifeSkills()).hasSize(1);
  }

  @Test
  void 스킬_코드는_폼으로_요청하고_128자_16진수만_꺼낸다() throws Exception {
    var code = "a1".repeat(64);
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://lostark.game.onstove.com/Profile/SkillRecommend"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("User-Agent", "LOAKraken"))
        .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("application/x-www-form-urlencoded")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string("memberNo=M1&worldNo=W1&pcId=P1"))
        .andRespond(
            withSuccess(
                "{\"code\":0,\"title\":\"스킬 코드\",\"content\":\"<p>다음 코드를 복사하세요</p><input value='"
                    + code
                    + "'>\",\"button\":\"코드 복사하기\"}",
                MediaType.APPLICATION_JSON));
    var result =
        new OfficialArmoryPageClient(policy(3_000_000, ""), builder)
            .skillCode(new com.loakraken.character.application.ArmoryPagePort.Identity("M1", "W1", "P1"));
    assertThat(result).isEqualTo(code.toUpperCase());
    server.verify();
  }

  @Test
  void 스킬_코드_거절은_우회하지_않고_접근_거부로_형식이_다르면_구조_변경으로_센다() {
    // 응답 code≠0(로그인 필요 안내 등)·401·403은 접근 거부.
    assertThatThrownBy(() -> OfficialArmoryPageClient.code("{\"code\":1,\"message\":\"로그인이 필요합니다\"}"))
        .isInstanceOfSatisfying(
            ArmoryPageFetchException.class,
            e -> assertThat(e.kind()).isEqualTo(Kind.ACCESS_DENIED));
    for (var status : java.util.List.of(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED)) {
      var builder = RestClient.builder();
      var server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("https://lostark.game.onstove.com/Profile/SkillRecommend")).andRespond(withStatus(status));
      assertThatThrownBy(
              () ->
                  new OfficialArmoryPageClient(policy(3_000_000, ""), builder)
                      .skillCode(
                          new com.loakraken.character.application.ArmoryPagePort.Identity("M", "W", "P")))
          .isInstanceOfSatisfying(
              ArmoryPageFetchException.class,
              e -> assertThat(e.kind()).isEqualTo(Kind.ACCESS_DENIED));
    }
    // JSON이 아니거나(안내 HTML), 코드가 없거나, 129자이거나, 영숫자 덩어리의 일부만 16진수이거나(G + 128자),
    // 서로 다른 값이 둘이거나, code가 정수 0이 아니면(0.5·"0"·없음) 구조 변경.
    var hex = "A".repeat(128);
    for (var body :
        java.util.List.of(
            "<html>로그인</html>",
            "{\"code\":0,\"content\":\"코드 없음\"}",
            "{\"code\":0,\"content\":\"" + "A".repeat(129) + "\"}",
            "{\"code\":0,\"content\":\"G" + hex + "\"}",
            "{\"code\":0,\"content\":\"" + hex + "g\"}",
            "{\"code\":0,\"content\":\"" + hex + " " + "B".repeat(128) + "\"}",
            "{\"code\":0.5,\"content\":\"" + hex + "\"}",
            "{\"code\":\"0\",\"content\":\"" + hex + "\"}",
            "{\"content\":\"" + hex + "\"}"))
      assertThatThrownBy(() -> OfficialArmoryPageClient.code(body))
          .as(body)
          .isInstanceOfSatisfying(
              ArmoryPageFetchException.class,
              e -> assertThat(e.kind()).isEqualTo(Kind.PARSE_ERROR));
    // 같은 값이 두 번 나오는 것은 괜찮다(복사 버튼 등).
    assertThat(
            OfficialArmoryPageClient.code(
                "{\"code\":0,\"content\":\"<input value='" + hex.toLowerCase() + "'><b>" + hex + "</b>\"}"))
        .isEqualTo(hex);
  }

  @Test
  void 연락_주소가_없으면_서비스_이름만_보낸다() throws Exception {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(header("User-Agent", "LOAKraken"))
        .andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
    new OfficialArmoryPageClient(policy(3_000_000, ""), builder).fetch("가");
    server.verify();
  }

  @Test
  void 응답_상태를_실패_종류로_나눈다() {
    assertKind(HttpStatus.TOO_MANY_REQUESTS, Kind.HTTP_ERROR);
    assertKind(HttpStatus.SERVICE_UNAVAILABLE, Kind.HTTP_ERROR);
    // 리다이렉트는 따라가지 않고 공식 쪽 문제로 센다(점검 안내 등).
    assertKind(HttpStatus.FOUND, Kind.HTTP_ERROR);
    assertKind(HttpStatus.FORBIDDEN, Kind.ACCESS_DENIED);
    assertKind(HttpStatus.UNAUTHORIZED, Kind.ACCESS_DENIED);
    assertKind(HttpStatus.NOT_FOUND, Kind.NOT_FOUND);
  }

  @Test
  void 본문이_상한을_넘으면_읽지_않는다() throws Exception {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo(URL + "%EA%B0%80"))
        .andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
    assertThatThrownBy(() -> new OfficialArmoryPageClient(policy(100, ""), builder).fetch("가"))
        .isInstanceOfSatisfying(
            ArmoryPageFetchException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TOO_LARGE));
  }

  @Test
  void 생활_스킬_목록이_없으면_구조_변경으로_실패한다() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo(URL + "%EA%B0%80"))
        .andRespond(withSuccess("<html><body><p>점검 중</p></body></html>", MediaType.TEXT_HTML));
    assertThatThrownBy(
            () -> new OfficialArmoryPageClient(policy(3_000_000, ""), builder).fetch("가"))
        .isInstanceOfSatisfying(
            ArmoryPageFetchException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PARSE_ERROR));
  }

  @Test
  void 레벨을_못_읽은_줄은_레벨만_null이다() {
    var page =
        ArmoryPageParser.parse(
            "<ul class='profile-skill-life__list'><li><strong>낚시</strong><span>-</span></li>"
                + "<li><strong> </strong><span>Lv.3</span></li></ul>");
    assertThat(page.lifeSkills()).hasSize(1);
    assertThat(page.lifeSkills().get(0).name()).isEqualTo("낚시");
    assertThat(page.lifeSkills().get(0).level()).isNull();
  }

  @Test
  void 다른_호스트나_http_주소는_설정에서_거부한다() {
    assertThatThrownBy(
            () ->
                new ArmoryPagePolicy(
                    true,
                    URI.create("https://example.com"),
                    "LOAKraken",
                    "",
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    1,
                    Duration.ofHours(6),
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    Duration.ofMinutes(1),
                    Duration.ofMinutes(30),
                    5,
                    3,
        new ArmoryPagePolicy.SkillCode(true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ArmoryPagePolicy(
                    true,
                    URI.create("http://lostark.game.onstove.com"),
                    "LOAKraken",
                    "",
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    1,
                    Duration.ofHours(6),
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    Duration.ofMinutes(1),
                    Duration.ofMinutes(30),
                    5,
                    3,
        new ArmoryPagePolicy.SkillCode(true)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertKind(HttpStatus status, Kind kind) {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(URL + "%EA%B0%80")).andRespond(withStatus(status));
    assertThatThrownBy(
            () -> new OfficialArmoryPageClient(policy(3_000_000, ""), builder).fetch("가"))
        .as(status.toString())
        .isInstanceOfSatisfying(
            ArmoryPageFetchException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(kind);
              assertThat(e.status()).isEqualTo(status.value());
            });
  }
}
