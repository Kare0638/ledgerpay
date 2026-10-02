package com.ledgerpay.mockpsp.api;

import com.ledgerpay.mockpsp.MockPspProperties;
import com.ledgerpay.mockpsp.fault.Fault;
import com.ledgerpay.mockpsp.operation.Operation;
import com.ledgerpay.mockpsp.operation.OperationService;
import com.ledgerpay.mockpsp.operation.SubmitRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PSP API (design §9.4). Submit is idempotent on {@code psp_request_id}: 201 when the operation
 * is new, 200 with its current state for a repeat. Inquiry is by the same ID, so a caller whose
 * submit timed out can find out whether it arrived.
 */
@RestController
@RequestMapping("/v1/operations")
class OperationController {

  private final OperationService operations;
  private final MockPspProperties properties;

  OperationController(OperationService operations, MockPspProperties properties) {
    this.operations = operations;
    this.properties = properties;
  }

  @PostMapping
  ResponseEntity<Operation> submit(@Valid @RequestBody SubmitRequest request) {
    var submitted = operations.submit(request);
    Operation operation = submitted.operation();
    if (!submitted.created()) {
      return ResponseEntity.ok(operation);
    }
    if (operation.fault() == Fault.TIMEOUT_AFTER_COMMIT) {
      // The operation is committed and will succeed; only the answer is lost to the caller.
      holdResponse(properties.faults().responseDelay());
    }
    return ResponseEntity.created(URI.create("/v1/operations/" + operation.pspRequestId()))
        .body(operation);
  }

  private static void holdResponse(Duration delay) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @GetMapping("/{pspRequestId}")
  ResponseEntity<?> inquire(@PathVariable String pspRequestId) {
    return operations
        .inquire(pspRequestId)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(
            () -> {
              ProblemDetail problem =
                  ProblemDetail.forStatusAndDetail(
                      HttpStatus.NOT_FOUND, "No operation with psp_request_id " + pspRequestId);
              problem.setProperty("code", "NOT_FOUND");
              return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
            });
  }
}
