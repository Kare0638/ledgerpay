package com.ledgerpay.notification.delivery;

/**
 * The merchant's endpoint did not accept the webhook; the delivery is retried, then dead-lettered.
 */
public class DeliveryFailedException extends RuntimeException {

  public DeliveryFailedException(String message) {
    super(message);
  }

  public DeliveryFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
