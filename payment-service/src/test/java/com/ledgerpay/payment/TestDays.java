package com.ledgerpay.payment;

import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Business days for tests that need one to themselves, long ago so no flow of today's runs into
 * them. Test classes share one database, and the reconciliation snapshot reads every operation of
 * the day: two tests drawing the same random day saw each other's operations (CI, PR #61, 4 extra
 * MISSING_EXTERNAL items). Here each call gets a new week, from a start drawn once per JVM, so no
 * two tests of a run share a day, nor the days next to it.
 */
public final class TestDays {

  private static final LocalDate START =
      LocalDate.of(2001, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(1_000));
  private static final AtomicInteger WEEKS = new AtomicInteger();

  private TestDays() {}

  public static LocalDate next() {
    return START.plusWeeks(WEEKS.getAndIncrement());
  }
}
