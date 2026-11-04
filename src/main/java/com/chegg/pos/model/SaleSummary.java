package com.chegg.pos.model;

import java.time.Instant;

public record SaleSummary(
        long id,
        String receiptNumber,
        String cashierName,
        String channel,
        long subtotalCents,
        long discountCents,
        long taxCents,
        long roundingCents,
        long totalCents,
        String status,
        Instant createdAt,
        int itemCount,
        String paymentSummary,
        Long kioskOrderId,
        long shiftId
) {
}
