package com.ledgerpay.payment;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Business days for tests that need one to themselves, long before any day today's flows write to.
 * Tests that share a Spring test context share its database, and the reconciliation snapshot reads
 * every operation of the day: two such tests drawing the same random day saw each other's
 * operations (CI on PR #61, four extra MISSING_EXTERNAL items). Each call gets the next week, so no
 * two tests of a run share a day or the days next to it. The start is fixed, not random: every run
 * has fresh containers anyway, and a fixed sequence lets a failure be replayed with the same days.
 */
public final class TestDays {

  private static final LocalDate START = LocalDate.of(2001, 1, 1);
  private static final AtomicInteger WEEKS = new AtomicInteger();

  private TestDays() {}

  public static LocalDate next() {
    return START.plusWeeks(WEEKS.getAndIncrement());
  }
}
