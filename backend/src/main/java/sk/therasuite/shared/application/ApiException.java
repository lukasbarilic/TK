package sk.therasuite.shared.application;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Očakávaná chyba s HTTP stavom a strojovo čitateľným kódom (mapuje ju GlobalExceptionHandler). */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final Map<String, Object> extensions;

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, Map.of());
  }

  /** {@code extensions} sa pridajú do ProblemDetail ako ďalšie polia (napr. {@code warnings}). */
  public ApiException(
      HttpStatus status, String code, String message, Map<String, Object> extensions) {
    super(message);
    this.status = status;
    this.code = code;
    this.extensions = Map.copyOf(extensions);
  }

  public Map<String, Object> getExtensions() {
    return extensions;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
