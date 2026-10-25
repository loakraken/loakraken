package com.loakraken.home.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 상점 공지 알림 배치(#636). 하루 한 번 공식 공지 목록을 읽어 상점·패키지 공지가 새로 오면 Slack에 한 번 알린다.
 *
 * @param enabled 기본 꺼짐(운영 Slack 웹훅을 붙인 뒤 켠다)
 * @param keywords 상점 분류가 아니어도 제목에 이 말이 있으면 상점 공지로 본다
 * @param reserve 공식 호출(실행당 1회) 때 공유 예산에 남길 사용자 몫
 * @param slackWebhookFile Slack 웹훅 주소가 든 비밀 파일 경로(주소는 설정·로그에 두지 않는다)
 */
@ConfigurationProperties("shop-notice")
public record ShopNoticePolicy(
    @DefaultValue("false") boolean enabled,
    @DefaultValue({"상점", "패키지", "로아샵", "상품"}) List<String> keywords,
    @DefaultValue("40") int reserve,
    @DefaultValue("") String slackWebhookFile) {
  public ShopNoticePolicy {
    keywords = keywords == null ? List.of() : List.copyOf(keywords);
  }
}
