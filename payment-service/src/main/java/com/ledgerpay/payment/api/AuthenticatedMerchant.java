package com.ledgerpay.payment.api;

/**
 * The merchant behind the request's API key. The merchant is always taken from here, never from the
 * request body (design §7).
 */
public record AuthenticatedMerchant(String id) {

  public static final String REQUEST_ATTRIBUTE = "ledgerpay.authenticatedMerchant";
}
