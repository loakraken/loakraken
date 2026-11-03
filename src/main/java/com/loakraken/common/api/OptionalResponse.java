package com.loakraken.common.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 새로 추가한 응답 필드를 계약의 required에서 뺀다. 응답은 늘 보내지만, 이 필드를 보내지 않는 이전 api가 배포돼 있는 동안 계약을 따르는 클라이언트가 깨지지 않게
 * 한다(배포 순서 규칙). api가 배포된 뒤 다음 릴리스에서 이 표시를 지워 필수로 돌린다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface OptionalResponse {}
