package com.chegg.pos.service.calc;

import com.chegg.pos.model.enums.PaymentMethod;

/** One tender line. Card tendered must equal the amount applied. */
public record TenderPayment(
        PaymentMethod method,
        long appliedCents,
        long tenderedCents,
        String reference
) {
    public TenderPayment {
        if (method == null) {
            throw new IllegalArgumentException("Payment method is required.");
        }
        if (appliedCents < 0 || tenderedCents < 0) {
            throw new IllegalArgumentException("Payment amounts cannot be negative.");
        }
    }

    public static TenderPayment cash(long appliedCents, long tenderedCents) {
        return new TenderPayment(PaymentMethod.CASH, appliedCents, tenderedCents, null);
    }

    public static TenderPayment card(long amountCents, String reference) {
        return new TenderPayment(PaymentMethod.CARD, amountCents, amountCents, reference);
    }
}
