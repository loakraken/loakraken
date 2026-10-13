/**
 * 시세 알림 웹 푸시(M33). 로그인 없이 브라우저별 익명 구독과 알림 조건을 저장하고, 시세 모듈이 예열로 받은 가격을 알리면 조건을 확인해 브라우저 푸시 서비스로 보낸다.
 * 공식 API를 부르지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "common::api",
      "common::lostark",
      "market::application",
      "market::domain"
    })
package com.loakraken.alerts;
