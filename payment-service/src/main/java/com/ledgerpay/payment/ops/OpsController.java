package com.ledgerpay.payment.ops;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;
import com.ledgerpay.payment.api.ResourceNotFoundException;
import com.ledgerpay.payment.psp.PspOperations;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operations endpoints, behind the ops key (design §7). */
@RestController
@RequestMapping("/ops")
public class OpsController {

  private static final Logger log = LoggerFactory.getLogger(OpsController.class);

  private final PspOperations operations;

  public OpsController(PspOperations operations) {
    this.operations = operations;
  }

  /**
   * Has the worker inquire about an operation now. Repeating it is harmless, so it needs no
   * idempotency key: an inquiry never moves money by itself.
   */
  @PostMapping("/psp-operations/{id}/inquiry")
  ResponseEntity<Map<String, Object>> inquire(@PathVariable UUID id) {
    switch (operations.requestInquiry(id)) {
      case SCHEDULED -> log.info("Ops requested an inquiry of PSP operation {}", id);
      case FINAL ->
          throw new ApiException(
              ErrorCode.INVALID_STATE, "PSP operation " + id + " already has its outcome");
      case IN_FLIGHT ->
          throw new ApiException(
              ErrorCode.INVALID_STATE,
              "PSP operation " + id + " is being called right now; try again when the call ends");
      case NOT_FOUND -> throw new ResourceNotFoundException("PSP operation " + id);
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(Map.of("pspOperationId", id, "inquiry", "SCHEDULED"));
  }
}
