package org.syskata.api.foundation;

public final class ApiFailure extends RuntimeException {
  private final int status;
  private final String code;

  public ApiFailure(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
