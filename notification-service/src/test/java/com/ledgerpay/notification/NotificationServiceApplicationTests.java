package com.ledgerpay.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceApplicationTests {

  @Autowired TestRestTemplate http;

  @Test
  void healthReportsUp() {
    String body = http.getForObject("/actuator/health", String.class);
    assertThat(body).contains("\"status\":\"UP\"");
  }
}
