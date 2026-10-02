package com.ledgerpay.mockpsp.fault;

import com.ledgerpay.mockpsp.operation.OperationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Selects faults for tests and demos. Exists only under the {@code dev} profile: anywhere else
 * these paths are 404 and no fault can be injected.
 */
@RestController
@Profile("dev")
@RequestMapping("/_admin/faults")
class FaultController {

  record AddRule(
      @NotBlank String merchantId,
      OperationType type,
      @NotNull Fault fault,
      @Min(1) Integer times) {}

  private final FaultRules rules;

  FaultController(FaultRules rules) {
    this.rules = rules;
  }

  @PostMapping
  ResponseEntity<FaultRule> add(@Valid @RequestBody AddRule request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(rules.add(request.merchantId(), request.type(), request.fault(), request.times()));
  }

  @GetMapping
  List<FaultRule> list() {
    return rules.list();
  }

  @DeleteMapping
  ResponseEntity<Void> clear() {
    rules.clear();
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> remove(@PathVariable UUID id) {
    return rules.remove(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
