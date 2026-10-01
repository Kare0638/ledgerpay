package com.ledgerpay.mockpsp.api;

import com.ledgerpay.mockpsp.operation.SubmitRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem Details with a {@code code}, as payment-service reports its own errors. */
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler(SubmitRejectedException.class)
  ProblemDetail rejected(SubmitRejectedException e) {
    HttpStatus status =
        switch (e.reason()) {
          case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
          case INVALID_REFERENCE -> HttpStatus.UNPROCESSABLE_ENTITY;
          case REQUEST_ID_CONFLICT -> HttpStatus.CONFLICT;
        };
    return problem(status, e.reason().name(), e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail invalid(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .sorted()
            .reduce((a, b) -> a + "; " + b)
            .orElse("Invalid request");
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail unreadable(HttpMessageNotReadableException e) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed request body");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return problem;
  }
}
