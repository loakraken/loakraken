package com.loakraken.character.application;

/** 공식 전투정보실 페이지 요청·해석 실패(#531). 메시지에 캐릭터 이름·본문을 넣지 않는다. */
public class ArmoryPageFetchException extends RuntimeException {
  /** 실패 종류. 지표 라벨(result)로도 쓴다. */
  public enum Kind {
    /** 429·5xx·3xx 등 공식 쪽 응답 문제(쉬었다가 다시) */
    HTTP_ERROR("http_error"),
    /** 연결·읽기 시간 초과, 연결 실패 */
    TIMEOUT("timeout"),
    /** 본문이 상한을 넘음 */
    TOO_LARGE("too_large"),
    /** 필요한 구조가 페이지에 없음(구조 변경 가능성) */
    PARSE_ERROR("parse_error"),
    /** 그 캐릭터 페이지가 없음(404) */
    NOT_FOUND("not_found"),
    /** 401·403 — 접근 제어. 우회하지 않고 멈춘다 */
    ACCESS_DENIED("access_denied");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private final Kind kind;
  private final Integer status;

  public ArmoryPageFetchException(Kind kind, Integer status) {
    super(kind.label() + (status == null ? "" : " status=" + status));
    this.kind = kind;
    this.status = status;
  }

  public Kind kind() {
    return kind;
  }

  public Integer status() {
    return status;
  }
}
