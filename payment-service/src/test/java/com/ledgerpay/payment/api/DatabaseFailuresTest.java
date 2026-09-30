package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;

class DatabaseFailuresTest {

  static Stream<Arguments> failures() {
    return Stream.of(
        // Temporary: 503, retry with the same key.
        Arguments.of(new CannotGetJdbcConnectionException("no connection"), true),
        Arguments.of(new TransientDataAccessResourceException("connection reset"), true),
        Arguments.of(new QueryTimeoutException("statement timeout"), true),
        Arguments.of(new CannotAcquireLockException("deadlock"), true),
        Arguments.of(new CannotCreateTransactionException("cannot begin"), true),
        Arguments.of(new TransactionSystemException("JDBC rollback failed"), true),
        // Bugs: 500.
        Arguments.of(new BadSqlGrammarException("q", "SELECT", new SQLException("syntax")), false),
        Arguments.of(new DataIntegrityViolationException("check violated"), false),
        Arguments.of(new DuplicateKeyException("unexpected duplicate"), false),
        Arguments.of(new IllegalTransactionStateException("no transaction"), false),
        Arguments.of(new IllegalStateException("not a database failure"), false));
  }

  @ParameterizedTest(name = "{0} -> temporary: {1}")
  @MethodSource("failures")
  void classifiesFailures(Exception failure, boolean temporary) {
    assertThat(DatabaseFailures.isTemporary(failure)).isEqualTo(temporary);
  }
}
