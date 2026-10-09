package com.loakraken.common.lostark;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 공유 호출 게이트(#107).
 *
 * @param store valkey(기본, 인스턴스 합산) 또는 memory(단일 인스턴스)
 * @param keyId 같은 공식 키를 쓰는 인스턴스끼리 공유하는 이름. 비우면 API 키 SHA-256 앞 12자. 운영·스테이징은 명시한다
 * @param commandTimeout 게이트 전용 Valkey 명령 타임아웃. 장애 시 빠르게 실패한다
 */
@ConfigurationProperties("lostark.gate")
public record LostArkGateProperties(
    @DefaultValue("valkey") String store,
    @DefaultValue("") String keyId,
    @DefaultValue("100ms") Duration commandTimeout) {}
