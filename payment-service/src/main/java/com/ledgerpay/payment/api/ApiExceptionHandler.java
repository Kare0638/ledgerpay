package com.ledgerpay.payment.api;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 7807 Problem Details with {@code code} and {@code traceId} (design
 * §7): {@link ApiException}s, database outages, Spring MVC's own errors such as malformed JSON or a
 * missing header, and anything unexpected. Errors raised in servlet filters never reach this class;
 * those filters write through {@link ProblemWriter} themselves.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Object> handleApiException(ApiException e) {
    return ResponseEntity.status(e.code().status())
        .body(ProblemWriter.problem(e.code(), e.getMessage()));
  }

  /**
   * Database and transaction failures. Temporary ones ({@link DatabaseFailures#isTemporary}) are
   * 503 with {@code Retry-After}; the rest are bugs and get the same 500 as anything unexpected.
   */
  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ResponseEntity<Object> handleDatabaseFailure(Exception e) {
    if (!DatabaseFailures.isTemporary(e)) {
      return handleUnexpected(e);
    }
    logger.warn("Database temporarily unavailable", e);
    return ResponseEntity.status(ErrorCode.TEMPORARILY_UNAVAILABLE.status())
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(ProblemWriter.RETRY_AFTER_SECONDS))
        .body(ProblemWriter.temporarilyUnavailable());
  }

  /** Anything unexpected: a 500 that reveals nothing but the trace ID to look up in the logs. */
  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception e) {
    logger.error("Unexpected error", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
        .body(ProblemWriter.problem(ErrorCode.INTERNAL_ERROR, "Unexpected error"));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = ex.getBody();
    problem.setDetail("Request validation failed");
    List<Map<String, String>> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    Map.of(
                        "field",
                        error.getField(),
                        "message",
                        String.valueOf(error.getDefaultMessage())))
            .toList();
    problem.setProperty("fieldErrors", fieldErrors);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  /** Spring MVC's own errors: add our fields to the Problem Details it has built. */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      ProblemWriter.decorate(problem, codeFor(status));
    }
    return super.createResponseEntity(body, headers, status, request);
  }

  /** Every status Spring MVC can produce has a code; anything else falls back to INTERNAL_ERROR. */
  static ErrorCode codeFor(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> ErrorCode.VALIDATION_ERROR;
      case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
      case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
      case 406 -> ErrorCode.NOT_ACCEPTABLE;
      case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
      case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
      case 503 -> ErrorCode.TEMPORARILY_UNAVAILABLE;
      default -> ErrorCode.INTERNAL_ERROR;
    };
  }
}
