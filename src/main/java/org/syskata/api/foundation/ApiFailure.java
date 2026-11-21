package org.syskata.api.foundation;

public final class ApiFailure extends RuntimeException {
  private final int status;
  private final String code;

  private final Long revision;

  public ApiFailure(int status, String code) {
    this(status, code, null);
  }

  public ApiFailure(int status, String code, Long revision) {
    super(code);
    this.revision = revision;
    this.status = status;
    this.code = code;
  }

  public Long revision() {
    return revision;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
