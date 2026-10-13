package com.ledgerpay.notification.delivery;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Whether a listener failure is this service's own database being unavailable, as opposed to the
 * event or the merchant. Same classes as payment-service's DatabaseFailures: unreachable, timeouts
 * and lock waits, no connection to begin with, commit or rollback lost with the connection.
 */
final class DatabaseFailures {

  private DatabaseFailures() {}

  /** Looks through the cause chain: the container wraps what the listener throws. */
  static boolean isTemporary(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataAccessResourceFailureException
          || cause instanceof TransientDataAccessException
          || cause instanceof CannotCreateTransactionException
          || cause instanceof TransactionSystemException) {
        return true;
      }
    }
    return false;
  }
}
