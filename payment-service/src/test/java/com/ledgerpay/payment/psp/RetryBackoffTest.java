package com.ledgerpay.payment.psp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryBackoffTest {

  static final Duration MAX = Duration.ofSeconds(60);

  /** Always the smallest or the largest value a bounded draw can return. */
  static RandomGenerator fixed(boolean largest) {
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return 0;
      }

      @Override
      public long nextLong(long bound) {
        return largest ? bound - 1 : 0;
      }
    };
  }

  @ParameterizedTest(name = "attempt {0}: {1}..{2} ms")
  @CsvSource({
    "1,  500,   1000",
    "2,  1000,  2000",
    "3,  2000,  4000",
    "4,  4000,  8000",
    "6,  16000, 32000",
    "7,  30000, 60000", // 64 s capped at 60
    "10, 30000, 60000",
    "100, 30000, 60000",
  })
  void doublesWithEqualJitterUpToTheCap(int attempts, long lowest, long highest) {
    assertThat(RetryBackoff.delay(attempts, MAX, fixed(false)))
        .isEqualTo(Duration.ofMillis(lowest));
    assertThat(RetryBackoff.delay(attempts, MAX, fixed(true)))
        .isEqualTo(Duration.ofMillis(highest));
    Random random = new Random(attempts);
    for (int i = 0; i < 1000; i++) {
      assertThat(RetryBackoff.delay(attempts, MAX, random).toMillis()).isBetween(lowest, highest);
    }
  }
}
