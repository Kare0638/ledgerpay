package com.ledgerpay.payment.ops;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;
import com.ledgerpay.payment.api.ResourceNotFoundException;
import com.ledgerpay.payment.ledger.integrity.IntegrityCheck;
import com.ledgerpay.payment.ledger.integrity.IntegrityChecks;
import com.ledgerpay.payment.ledger.integrity.LedgerIntegrity;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ledger integrity checks on demand (design §11.2, #19), behind the ops key. A check is quick and
 * read-only, so it runs within the request: 201 with its result, PASSED or FAILED alike, since a
 * failure is a finding about the books, not about the request.
 */
@RestController
@RequestMapping("/ops/ledger-integrity-checks")
class LedgerIntegrityController {

  static final int MAX_LIST = 100;

  private final LedgerIntegrity integrity;
  private final IntegrityChecks checks;

  LedgerIntegrityController(LedgerIntegrity integrity, IntegrityChecks checks) {
    this.integrity = integrity;
    this.checks = checks;
  }

  @PostMapping
  ResponseEntity<IntegrityCheck> run() {
    IntegrityCheck check = integrity.runOnDemand();
    return ResponseEntity.created(URI.create("/ops/ledger-integrity-checks/" + check.checkId()))
        .body(check);
  }

  @GetMapping("/{id}")
  IntegrityCheck get(@PathVariable UUID id) {
    return checks
        .find(id)
        .orElseThrow(() -> new ResourceNotFoundException("Ledger integrity check " + id));
  }

  /** The latest checks, on demand or with a reconciliation, newest first. */
  @GetMapping
  List<IntegrityCheck> latest(@RequestParam(defaultValue = "20") int limit) {
    if (limit < 1 || limit > MAX_LIST) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit must be 1 to " + MAX_LIST);
    }
    return checks.latest(limit);
  }
}
