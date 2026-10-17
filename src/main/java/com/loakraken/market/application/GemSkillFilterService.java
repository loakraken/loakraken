package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.market.domain.GemSkillCatalog;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 보석 매물 직업·스킬 조건(정적 목록, 공식 호출 없음). 틀린 조건은 입력값을 되돌리지 않는 고정 문장의 400이다. */
@Service
public class GemSkillFilterService {
  private final GemSkillCatalog catalog;

  public GemSkillFilterService(GemSkillCatalog catalog) {
    this.catalog = catalog;
  }

  public GemSkillCatalog catalog() {
    return catalog;
  }

  public Optional<GemSkillCatalog.Filter> filter(String kind, String className, Integer skill) {
    try {
      return catalog.filter(kind, blankToNull(className), skill);
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(MarketErrorCode.INVALID_GEM_FILTER);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
