package com.loakraken.character.application;

import com.loakraken.common.api.BusinessException;

public class CharacterException extends BusinessException {
  public CharacterException(CharacterErrorCode errorCode) {
    super(errorCode);
  }
}
