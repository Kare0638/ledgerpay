package com.ledgerpay.payment.api;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Decides whether a database failure is temporary: the outcome is unknown or nothing was accepted,
 * so the client should get 503 and retry with the same Idempotency-Key, which makes the retry safe.
 * Shared by {@link ApiExceptionHandler} and {@link ApiKeyAuthFilter} so both classify the same way.
 */
public final class DatabaseFailures {

  private DatabaseFailures() {}

  /**
   * <ul>
   *   <li>{@link DataAccessResourceFailureException}: the database is unreachable or the connection
   *       died during a statement;
   *   <li>{@link TransientDataAccessException}: timeouts, deadlocks and lock waits, which may
   *       succeed if retried;
   *   <li>{@link CannotCreateTransactionException}: no connection to begin a transaction with;
   *   <li>{@link TransactionSystemException}: commit or rollback failed. This is typically a
   *       connection lost mid-transaction, and it replaces the original exception.
   * </ul>
   *
   * Anything else, such as bad SQL or a constraint violation, is a bug and not worth retrying.
   */
  public static boolean isTemporary(Throwable e) {
    return e instanceof DataAccessResourceFailureException
        || e instanceof TransientDataAccessException
        || e instanceof CannotCreateTransactionException
        || e instanceof TransactionSystemException;
  }
}
