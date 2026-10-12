package com.ledgerpay.notification.endpoint;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registers a merchant's webhook endpoint (ops key). The response is the only place the signing
 * secret is shown; the merchant verifies webhooks with it.
 */
@RestController
class EndpointController {

  private final MerchantEndpoints endpoints;

  EndpointController(MerchantEndpoints endpoints) {
    this.endpoints = endpoints;
  }

  @PutMapping("/ops/merchants/{merchantId}/webhook-endpoint")
  RegisteredEndpoint register(
      @PathVariable String merchantId, @Valid @RequestBody EndpointRequest request) {
    MerchantEndpoint endpoint = endpoints.register(merchantId, URI.create(request.url()));
    return new RegisteredEndpoint(
        endpoint.merchantId(), endpoint.webhookUrl().toString(), endpoint.signingSecret());
  }

  record EndpointRequest(@NotBlank @Pattern(regexp = "https?://\\S+") String url) {}

  record RegisteredEndpoint(String merchantId, String url, String signingSecret) {}
}
