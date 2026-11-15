/**
 * 스펙업 가이드(#315). 캐릭터 캐시의 현재 세팅에서 후보 업그레이드마다 전투력 시뮬레이터로 Δ전투력을, 시세 캐시로 비용을 구해 골드당 효율로 정렬한다. 공식 API를
 * 부르지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "common::api",
      // 계산 자리 클라이언트당 1개(보안 11-16 강화 (a)): 요청의 클라이언트 키만 읽는다.
      "common::lostark",
      "character::application",
      "character::domain",
      "market::application",
      "refining::application",
      "refining::domain"
    })
package com.loakraken.specup;
