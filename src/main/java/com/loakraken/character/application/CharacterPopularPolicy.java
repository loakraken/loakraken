package com.loakraken.character.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인기 검색 캐릭터(F-10, popular-v1) 설정.
 *
 * @param minUniqueViews 기간 안 고유 조회 수가 이보다 작으면 순위에 넣지 않는다.
 * @param minSample 기간 안 전체 고유 조회 수가 이보다 작으면 COLLECTING이고 목록을 비운다(통계와 같은 30).
 * @param retention 시간별 집계 보관 기간(7일 탭 + 여유).
 */
@ConfigurationProperties("character.popular")
public record CharacterPopularPolicy(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("5") int minUniqueViews,
    @DefaultValue("30") int minSample,
    @DefaultValue("10m") Duration cacheTtl,
    @DefaultValue("8d") Duration retention) {}
