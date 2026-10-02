package com.ledgerpay.payment.psp;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * When to try a PSP operation again after a call established nothing (design §9.1): 1, 2, 4, 8 …
 * seconds, capped, with "equal jitter" (half the delay fixed, half random) so that operations
 * failing together do not all come back together.
 */
public final class RetryBackoff {

  private RetryBackoff() {}

  /**
   * @param attempts attempts made so far, including the one that just failed
   */
  public static Duration delay(int attempts, Duration max, RandomGenerator random) {
    long maxMillis = max.toMillis();
    int exponent = Math.min(Math.max(attempts - 1, 0), 30);
    long base = Math.min(1000L << exponent, maxMillis);
    long half = base / 2;
    return Duration.ofMillis(half + random.nextLong(base - half + 1));
  }
}
