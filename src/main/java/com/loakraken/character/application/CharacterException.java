package com.loakraken.character.application;

import com.loakraken.common.api.BusinessException;
import java.time.Duration;

public class CharacterException extends BusinessException {
  public CharacterException(CharacterErrorCode errorCode) {
    super(errorCode);
  }

  public CharacterException(CharacterErrorCode errorCode, Duration retryAfter) {
    super(errorCode, retryAfter);
  }
}
