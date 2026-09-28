package com.ledgerpay.payment.api;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders {@link ApiException}s as RFC 7807 Problem Details with an extra {@code code} field. */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handle(ApiException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.code().status(), e.getMessage());
    problem.setProperty("code", e.code().name());
    return ResponseEntity.status(e.code().status()).body(problem);
  }
}
