package com.ledgerpay.mockpsp.webhook;

import com.ledgerpay.common.webhook.WebhookSignature;
import com.ledgerpay.mockpsp.MockPspProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends due webhooks, signed at send time, outside any transaction. Anything but a 2xx is retried
 * with backoff, so the receiver must acknowledge only after it has persisted the event.
 */
@Component
public class WebhookDispatcher {

  private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

  private final WebhookEvents events;
  private final MockPspProperties properties;
  private final Clock clock;
  private final RestClient http;
  private final byte[] secret;

  public WebhookDispatcher(
      WebhookEvents events, MockPspProperties properties, Clock clock, RestClient.Builder builder) {
    this.events = events;
    this.properties = properties;
    this.clock = clock;
    var webhook = properties.webhook();
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(webhook.connectTimeout());
    requestFactory.setReadTimeout(webhook.readTimeout());
    this.http = builder.requestFactory(requestFactory).build();
    this.secret = webhook.secret().getBytes(StandardCharsets.UTF_8);
  }

  /** Sends one batch of due webhooks and returns how many were acknowledged. */
  public int dispatchDue() {
    var webhook = properties.webhook();
    int delivered = 0;
    for (var due :
        events.claimDue(properties.batchSize(), webhook.lease(), webhook.maxAttempts())) {
      if (deliver(due)) {
        delivered++;
      }
    }
    return delivered;
  }

  private boolean deliver(WebhookEvents.Due due) {
    byte[] body = due.payload().getBytes(StandardCharsets.UTF_8);
    long timestamp = clock.instant().getEpochSecond();
    try {
      http.post()
          .uri(properties.webhook().url())
          .contentType(MediaType.APPLICATION_JSON)
          .header(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(timestamp))
          .header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, timestamp, body))
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      log.warn("Webhook {} attempt {} failed: {}", due.eventId(), due.attempts(), e.getMessage());
      events.markFailed(due.eventId(), due.attempts(), e.getMessage());
      return false;
    }
    events.markDelivered(due.eventId());
    return true;
  }
}
