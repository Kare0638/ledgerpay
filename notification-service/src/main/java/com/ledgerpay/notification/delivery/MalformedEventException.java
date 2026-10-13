package com.ledgerpay.notification.delivery;

/** An event that can never be delivered as it is: it goes to the dead-letter topic at once. */
public class MalformedEventException extends RuntimeException {

  public MalformedEventException(String reason) {
    super("Malformed payment event: " + reason);
  }
}
