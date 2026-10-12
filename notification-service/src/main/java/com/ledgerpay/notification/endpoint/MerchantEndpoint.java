package com.ledgerpay.notification.endpoint;

import java.net.URI;

/** Where a merchant's webhooks go, and the secret they are signed with. */
public record MerchantEndpoint(String merchantId, URI webhookUrl, String signingSecret) {}
