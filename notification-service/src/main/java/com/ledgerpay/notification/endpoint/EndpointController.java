package com.ledgerpay.notification.endpoint;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    MerchantEndpoint endpoint = endpoints.register(merchantId, webhookUrl(request.url()));
    return new RegisteredEndpoint(
        endpoint.merchantId(), endpoint.webhookUrl().toString(), endpoint.signingSecret());
  }

  /**
   * The pattern lets through strings such as {@code http://exa^mple.com} that are no URI, and
   * {@code http://:80} that has no host to send to: both are the caller's mistake, a 400.
   */
  private static URI webhookUrl(String url) {
    try {
      URI uri = new URI(url);
      if (uri.getHost() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url has no host: " + url);
      }
      return uri;
    } catch (URISyntaxException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "url is not a valid URI: " + e.getMessage());
    }
  }

  record EndpointRequest(@NotBlank @Pattern(regexp = "https?://\\S+") String url) {}

  record RegisteredEndpoint(String merchantId, String url, String signingSecret) {}
}
