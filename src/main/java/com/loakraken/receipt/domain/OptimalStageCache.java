package com.loakraken.receipt.domain;

import com.loakraken.refining.domain.RefiningOptimal;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 회차별 최적 계산 결과 캐시(#292). 키는 트랙·단계·성장 지원·책 허용과 그 단계 재료 시세 값이라 시세가 바뀌면 새로 계산한다. 운영 구현은 요청 사이에 나눠 쓰는
 * Spring 빈이고(크기·적중률 지표), {@link #NONE}은 캐시 없이 매번 계산한다.
 */
public interface OptimalStageCache {
  Optional<RefiningOptimal.Result> get(String key, Supplier<RefiningOptimal.Result> compute);

  OptimalStageCache NONE = (key, compute) -> Optional.ofNullable(compute.get());
}
