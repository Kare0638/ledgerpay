package com.ledgerpay.payment.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AnalysisValidatorTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String FACTS =
      "{\"ourOperationAtSnapshot\": {\"id\": \"0b8e3a52-6c1d-4f4e-9a59-1f1e9c3b2a10\","
          + " \"pspReference\": \"psp_abc\", \"pspRequestId\": \"req_xyz\"}}";

  JsonNode args(String json) throws Exception {
    return JSON.readTree(json);
  }

  @Test
  void aWellFormedAnalysisCitingKnownIdsIsAccepted() throws Exception {
    var result =
        AnalysisValidator.validate(
            args(
                """
                {"likelyCause": "OUTCOME_NOT_RECEIVED", "confidence": "HIGH",
                 "explanation": "psp_abc succeeded at the PSP; 0b8e3a52-6c1d-4f4e-9a59-1f1e9c3b2a10 is PENDING.",
                 "evidence": ["no webhook received for req_xyz"], "nextSteps": ["Confirm the inquiry"]}"""),
            FACTS);

    assertThat(result.valid()).isTrue();
    assertThat(result.analysis().likelyCause()).isEqualTo(LikelyCause.OUTCOME_NOT_RECEIVED);
    assertThat(result.analysis().evidence()).containsExactly("no webhook received for req_xyz");
  }

  @Test
  void anIdTheToolsNeverReturnedIsRefused() throws Exception {
    var result =
        AnalysisValidator.validate(
            args(
                """
                {"likelyCause": "OUTCOME_NOT_RECEIVED", "confidence": "HIGH",
                 "explanation": "Payment 11111111-2222-3333-4444-555555555555 is stuck.",
                 "evidence": ["psp_invented has no webhook"], "nextSteps": []}"""),
            FACTS);

    assertThat(result.valid()).isFalse();
    assertThat(result.errors())
        .singleElement()
        .asString()
        .contains("11111111-2222-3333-4444-555555555555", "psp_invented");
  }

  @Test
  void theSchemaIsEnforced() throws Exception {
    var result =
        AnalysisValidator.validate(
            args(
                """
                {"likelyCause": "GREMLINS", "confidence": "SURE", "explanation": "",
                 "evidence": [], "nextSteps": ["a", "b", "c", "d", "e", "f"]}"""),
            FACTS);

    assertThat(result.valid()).isFalse();
    assertThat(result.errors())
        .hasSize(5)
        .anyMatch(e -> e.startsWith("likelyCause must be one of"))
        .anyMatch(e -> e.startsWith("confidence must be one of"))
        .contains(
            "explanation is required",
            "evidence must have 1 to 8 entries",
            "nextSteps must have 0 to 5 entries");
  }

  @Test
  void overlongTextIsRefused() throws Exception {
    String long1300 = "x".repeat(1_300);
    var result =
        AnalysisValidator.validate(
            args(
                """
                {"likelyCause": "FIELD_MISMATCH", "confidence": "LOW", "explanation": "%s",
                 "evidence": ["%s"]}"""
                    .formatted(long1300, "y".repeat(301))),
            FACTS);

    assertThat(result.errors())
        .containsExactly(
            "explanation is longer than 1200 characters",
            "evidence entries must be at most 300 characters");
  }
}
