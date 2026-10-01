package com.ledgerpay.payment.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.webhook.WebhookSignature;
import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;
import com.ledgerpay.payment.psp.PspProperties;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound PSP webhooks (design §9.2). The raw body is verified before it is parsed, and 200 is
 * returned only once the event is committed to the inbox: if the database is down the PSP gets 503
 * and redelivers. Processing happens afterwards, in {@link InboxProcessor}.
 */
@RestController
class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  private final Inbox inbox;
  private final ObjectMapper json;
  private final Validator validator;
  private final Clock clock;
  private final String provider;
  private final byte[] secret;

  WebhookController(
      Inbox inbox, ObjectMapper json, Validator validator, Clock clock, PspProperties properties) {
    this.inbox = inbox;
    this.json = json;
    this.validator = validator;
    this.clock = clock;
    this.provider = properties.provider();
    this.secret = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping("/webhooks/psp")
  ResponseEntity<Void> receive(
      @RequestHeader(name = WebhookSignature.TIMESTAMP_HEADER, required = false) String timestamp,
      @RequestHeader(name = WebhookSignature.SIGNATURE_HEADER, required = false) String signature,
      @RequestBody byte[] body) {
    if (!WebhookSignature.verify(secret, timestamp, body, signature, clock.instant())) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid webhook signature");
    }
    WebhookEvent event = parse(body);
    String payload = new String(body, StandardCharsets.UTF_8);
    switch (inbox.receive(provider, event.eventId(), payload, sha256(body))) {
      case NEW, DUPLICATE -> {}
      case CONFLICT ->
          // Acknowledged so the PSP stops redelivering, but the original is kept: alert.
          log.error(
              "Webhook {} from {} conflicts with the stored event of the same ID; kept the original",
              event.eventId(),
              provider);
    }
    return ResponseEntity.ok().build();
  }

  private WebhookEvent parse(byte[] body) {
    WebhookEvent event;
    try {
      event = json.readValue(body, WebhookEvent.class);
    } catch (IOException e) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "Malformed webhook body");
    }
    var violations = validator.validate(event);
    if (!violations.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          violations.stream()
              .map(v -> v.getPropertyPath() + " " + v.getMessage())
              .sorted()
              .collect(Collectors.joining("; ")));
    }
    return event;
  }

  private static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
