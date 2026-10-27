package com.ledgerpay.payment.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reconciliation assistant's endpoints (design §7, §16), behind the ops key like every /ops
 * path. Drafting an analysis calls the model within the request, which takes seconds.
 */
@RestController
class AssistantController {

  /** Who decides, as they give it: recorded with the decision. */
  record Decision(@NotBlank @Size(max = 100) String decidedBy) {}

  private final Assistant assistant;

  AssistantController(Assistant assistant) {
    this.assistant = assistant;
  }

  @PostMapping("/ops/recon-runs/{runId}/items/{itemNo}/analysis")
  ResponseEntity<StoredAnalysis> analyse(@PathVariable UUID runId, @PathVariable int itemNo) {
    StoredAnalysis analysis = assistant.analyse(runId, itemNo);
    return ResponseEntity.created(URI.create("/ops/assistant/analyses/" + analysis.analysisId()))
        .body(analysis);
  }

  @GetMapping("/ops/assistant/analyses/{id}")
  StoredAnalysis get(@PathVariable UUID id) {
    return assistant.get(id);
  }

  @PostMapping("/ops/assistant/proposals/{id}/confirm")
  StoredAnalysis confirm(@PathVariable UUID id, @Valid @RequestBody Decision decision) {
    return assistant.confirm(id, decision.decidedBy());
  }

  @PostMapping("/ops/assistant/proposals/{id}/reject")
  StoredAnalysis reject(@PathVariable UUID id, @Valid @RequestBody Decision decision) {
    return assistant.reject(id, decision.decidedBy());
  }
}
