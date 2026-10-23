package com.ledgerpay.payment.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace ID: in the MDC for logs, in the {@code X-Trace-Id} response header,
 * and in response bodies. Also puts the request's {@code Idempotency-Key} and the payment its path
 * names in the MDC, so every log line of the request carries them (design §13). The key is not a
 * secret; the Authorization header never goes there.
 */
public class TraceIdFilter extends OncePerRequestFilter {

  private static final Pattern PAYMENT_PATH =
      Pattern.compile("^/v1/payments/([0-9a-fA-F-]{36})(?:/.*)?$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    MDC.put(TraceIds.MDC_KEY, traceId);
    String key = request.getHeader("Idempotency-Key");
    if (key != null) {
      MDC.put("idempotencyKey", key.length() > 255 ? key.substring(0, 255) : key);
    }
    Matcher payment = PAYMENT_PATH.matcher(request.getRequestURI());
    if (payment.matches()) {
      MDC.put("paymentId", payment.group(1));
    }
    response.setHeader(TraceIds.HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TraceIds.MDC_KEY);
      MDC.remove("idempotencyKey");
      MDC.remove("paymentId");
    }
  }
}
