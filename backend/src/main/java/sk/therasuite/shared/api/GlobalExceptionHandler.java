package sk.therasuite.shared.api;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import sk.therasuite.shared.application.ApiException;

/** Jednotný formát chýb: {@link ProblemDetail} (RFC 9457) s poľom {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String CONFLICT = "CONFLICT";
  public static final String BAD_REQUEST = "BAD_REQUEST";
  public static final String FORBIDDEN = "FORBIDDEN";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      org.springframework.http.HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Neplatný vstup.");
    List<FieldViolation> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @Override
  protected ResponseEntity<Object> handleNoResourceFoundException(
      NoResourceFoundException ex,
      org.springframework.http.HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Zdroj sa nenašiel."));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      org.springframework.http.HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail pd) {
      pd.setProperty("code", statusCode.value() == 404 ? NOT_FOUND : BAD_REQUEST);
    }
    return response;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Neplatný vstup.");
    List<FieldViolation> errors =
        ex.getConstraintViolations().stream()
            .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            problem(
                HttpStatus.CONFLICT,
                CONFLICT,
                "Záznam medzitým zmenil iný používateľ. Načítajte ho znova."));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
    ProblemDetail problem = problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    ex.getExtensions().forEach(problem::setProperty);
    return ResponseEntity.status(ex.getStatus()).body(problem);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(problem(HttpStatus.FORBIDDEN, FORBIDDEN, "Na túto akciu nemáte oprávnenie."));
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return problem;
  }

  /** Jedno porušené pravidlo vstupu. */
  public record FieldViolation(String field, String message) {}
}
