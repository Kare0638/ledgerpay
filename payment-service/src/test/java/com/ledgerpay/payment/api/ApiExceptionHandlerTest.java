package com.ledgerpay.payment.api;

import static com.ledgerpay.payment.payment.PaymentStatus.CAPTURE_PENDING;
import static com.ledgerpay.payment.payment.PaymentStatus.VOIDED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
  }

  @Autowired MockMvc mvc;

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
