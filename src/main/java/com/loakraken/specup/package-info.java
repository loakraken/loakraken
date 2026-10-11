/**
 * 스펙업 가이드(#315). 캐릭터 캐시의 현재 세팅에서 후보 업그레이드마다 전투력 시뮬레이터로 Δ전투력을, 시세 캐시로 비용을 구해 골드당 효율로 정렬한다. 공식 API를
 * 부르지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "common::api",
      "character::application",
      "character::domain",
      "market::application"
    })
package com.loakraken.specup;
