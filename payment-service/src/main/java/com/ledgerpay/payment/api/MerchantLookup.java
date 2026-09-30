package com.ledgerpay.payment.api;

import java.util.Optional;

/** Finds the merchant that owns an API key, by the key's SHA-256 ({@link ApiKeys#hash}). */
@FunctionalInterface
public interface MerchantLookup {

  Optional<AuthenticatedMerchant> byApiKeyHash(String apiKeyHash);
}
