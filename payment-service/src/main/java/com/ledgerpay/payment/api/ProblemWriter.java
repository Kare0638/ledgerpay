package com.ledgerpay.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds the Problem Details body used for every error (design §7): RFC 7807 plus {@code code} and
 * {@code traceId}. Also writes it directly for filters, which run before controller advice.
 */
@Component
public class ProblemWriter {

  public static final int RETRY_AFTER_SECONDS = 5;

  private final ObjectMapper json;

  public ProblemWriter(ObjectMapper json) {
    this.json = json;
  }

  public static ProblemDetail problem(ErrorCode code, String detail) {
    return decorate(ProblemDetail.forStatusAndDetail(code.status(), detail), code);
  }

  /**
   * The database is unreachable, so nothing was accepted: the client should retry with the same
   * Idempotency-Key. Sent with a {@code Retry-After} header of the same value.
   */
  public static ProblemDetail temporarilyUnavailable() {
    ProblemDetail problem =
        problem(
            ErrorCode.TEMPORARILY_UNAVAILABLE, "Temporarily unavailable; retry with the same key");
    problem.setProperty("retryAfterSeconds", RETRY_AFTER_SECONDS);
    return problem;
  }

  /** Adds {@code code} (unless already set) and {@code traceId} to a problem built elsewhere. */
  public static ProblemDetail decorate(ProblemDetail problem, ErrorCode code) {
    if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
      problem.setProperty("code", code.name());
    }
    problem.setProperty("traceId", TraceIds.current());
    return problem;
  }

  public void write(HttpServletResponse response, ErrorCode code, String detail)
      throws IOException {
    write(response, problem(code, detail));
  }

  public void writeTemporarilyUnavailable(HttpServletResponse response) throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
    write(response, temporarilyUnavailable());
  }

  private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
