package com.ledgerpay.payment.api;

/**
 * The resource does not exist, or belongs to another merchant: both return the same 404 so that
 * existence is not leaked (design §7).
 */
public class ResourceNotFoundException extends ApiException {

  public ResourceNotFoundException(String what) {
    super(ErrorCode.RESOURCE_NOT_FOUND, what + " not found");
  }
}
