package com.ledgerpay.payment.api;

import com.ledgerpay.payment.payment.PaymentService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Refunds are created under their payment ({@link PaymentController}) and read here. */
@RestController
@RequestMapping("/v1/refunds")
public class RefundController {

  private final PaymentService payments;

  public RefundController(PaymentService payments) {
    this.payments = payments;
  }

  @GetMapping("/{id}")
  RefundView get(
      @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant,
      @PathVariable UUID id) {
    return payments
        .findRefund(merchant.id(), id)
        .map(RefundView::of)
        .orElseThrow(() -> new ResourceNotFoundException("Refund " + id));
  }
}
