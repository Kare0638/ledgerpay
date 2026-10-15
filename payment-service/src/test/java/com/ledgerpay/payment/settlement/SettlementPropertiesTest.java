package com.ledgerpay.payment.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class SettlementPropertiesTest {

  @Test
  void theDefaultLimitsAreTheDesignsTenMegabytesAndHundredThousandRows() {
    SettlementProperties properties =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("ledgerpay.settlement", SettlementProperties.class);

    assertThat(properties.maxFileSize()).isEqualTo(DataSize.ofMegabytes(10));
    assertThat(properties.maxRows()).isEqualTo(100_000);
    assertThat(properties.maxStoredErrors()).isEqualTo(1_000);
  }
}
