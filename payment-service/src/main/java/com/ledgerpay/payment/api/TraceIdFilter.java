package com.ledgerpay.payment.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace ID: in the MDC for logs, in the {@code X-Trace-Id} response header,
 * and in response bodies. A stand-in until distributed tracing arrives with observability (M3).
 */
public class TraceIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    MDC.put(TraceIds.MDC_KEY, traceId);
    response.setHeader(TraceIds.HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TraceIds.MDC_KEY);
    }
  }
}
