package com.ledgerpay.notification.delivery;

import com.ledgerpay.common.webhook.WebhookSignature;
import com.ledgerpay.notification.NotificationProperties;
import com.ledgerpay.notification.endpoint.MerchantEndpoint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends a merchant webhook: a POST of the event envelope, signed as PSP webhooks are (design §9.2)
 * with the merchant's own secret. The merchant verifies {@code HMAC-SHA256(secret, timestamp + "."
 * + body)} against {@link #SIGNATURE_HEADER} and de-duplicates on {@link #EVENT_ID_HEADER}, because
 * delivery is at-least-once.
 */
@Component
class WebhookSender {

  static final String EVENT_ID_HEADER = "X-LedgerPay-Event-Id";
  static final String EVENT_TYPE_HEADER = "X-LedgerPay-Event-Type";
  static final String TIMESTAMP_HEADER = "X-LedgerPay-Timestamp";
  static final String SIGNATURE_HEADER = "X-LedgerPay-Signature";

  private final RestClient http;
  private final Clock clock;

  WebhookSender(RestClient.Builder builder, NotificationProperties properties, Clock clock) {
    // A blocking client on the listener thread: one delivery at a time per partition, and no
    // executor of the JDK HTTP client to starve on a small machine (ADR 0008).
    var requests = new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(properties.connectTimeout());
    requests.setReadTimeout(properties.readTimeout());
    this.http = builder.requestFactory(requests).build();
    this.clock = clock;
  }

  /** Returns the merchant's 2xx status; anything else is a {@link DeliveryFailedException}. */
  int send(MerchantEndpoint endpoint, PaymentEvent event) {
    byte[] body = event.raw().getBytes(StandardCharsets.UTF_8);
    long timestamp = clock.instant().getEpochSecond();
    String signature =
        WebhookSignature.sign(
            endpoint.signingSecret().getBytes(StandardCharsets.UTF_8), timestamp, body);
    try {
      return http.post()
          .uri(endpoint.webhookUrl())
          .contentType(MediaType.APPLICATION_JSON)
          .header(EVENT_ID_HEADER, event.eventId().toString())
          .header(EVENT_TYPE_HEADER, event.eventType())
          .header(TIMESTAMP_HEADER, Long.toString(timestamp))
          .header(SIGNATURE_HEADER, signature)
          .body(body)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (!response.getStatusCode().is2xxSuccessful()) {
                  throw new DeliveryFailedException(
                      "Merchant " + endpoint.merchantId() + " answered " + status);
                }
                return status;
              });
    } catch (RestClientException e) {
      throw new DeliveryFailedException(
          "Merchant " + endpoint.merchantId() + " unreachable: " + e.getMessage(), e);
    }
  }
}
