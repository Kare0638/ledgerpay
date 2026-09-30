package com.ledgerpay.payment.api;

import static com.ledgerpay.payment.payment.PaymentStatus.CAPTURE_PENDING;
import static com.ledgerpay.payment.payment.PaymentStatus.VOIDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ApiExceptionHandlerTest.CaptureStubController.class)
@Import(ApiExceptionHandlerTest.CaptureStubController.class)
class ApiExceptionHandlerTest {

  /** Stands in for the real capture endpoint: capturing a voided payment is illegal. */
  @RestController
  static class CaptureStubController {

    @PostMapping("/test/capture-voided-payment")
    void capture() {
      VOIDED.transitionTo(CAPTURE_PENDING);
    }

    @PostMapping("/test/bug")
    void bug() {
      throw new IllegalStateException("secret internal detail");
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void anUnexpectedErrorIs500WithACodeAndNoInternals() throws Exception {
    mvc.perform(post("/test/bug"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value("Unexpected error"));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(HttpStatus.class)
  void everyErrorStatusMapsToACodeWithThatStatusOrInternalError(HttpStatus status) {
    if (status.value() < 400) {
      return;
    }
    ErrorCode code = ApiExceptionHandler.codeFor(status);

    assertThat(code).isNotNull();
    // Compare numbers: HttpStatus has deprecated aliases (413 is also REQUEST_ENTITY_TOO_LARGE).
    assertThat(code.status().value() == status.value() || code == ErrorCode.INTERNAL_ERROR)
        .as("%s -> %s", status, code)
        .isTrue();
  }

  @Test
  void illegalTransitionIs409InvalidStateProblemDetails() throws Exception {
    mvc.perform(post("/test/capture-voided-payment"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("INVALID_STATE"))
        .andExpect(
            jsonPath("$.detail").value("Payment in status VOIDED cannot move to CAPTURE_PENDING"));
  }
}
