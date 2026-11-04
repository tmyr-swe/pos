package com.chegg.pos.service;

public record CheckoutResult(
        long saleId,
        String receiptNumber,
        long totalCents,
        long changeCents,
        long roundingCents
) {
}
