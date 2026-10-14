/**
 * 통계·랭킹(B-3·F-7 2단계). 캐릭터 모듈이 관측·수집한 characters 테이블을 읽기 전용으로 집계한다. 공식 API를 부르지 않는다. 빌드 통계의 캐릭터 한 명
 * 칸(M26 2단계)은 캐릭터 모듈의 캐시·저장 요약(공식 호출 없음)을 읽는다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common::api", "character::application"})
package com.loakraken.stats;
