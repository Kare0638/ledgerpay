package com.ledgerpay.payment.api;

import org.slf4j.MDC;

/** The current request's trace ID, set by {@link TraceIdFilter} and included in every response. */
public final class TraceIds {

  public static final String MDC_KEY = "traceId";
  public static final String HEADER = "X-Trace-Id";

  private TraceIds() {}

  public static String current() {
    return MDC.get(MDC_KEY);
  }
}
