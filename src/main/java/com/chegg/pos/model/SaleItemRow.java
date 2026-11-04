package com.chegg.pos.model;

import java.time.Instant;

public record SaleItemRow(
        long id,
        int lineNo,
        long productId,
        String sku,
        String name,
        long unitPriceCents,
        int quantity,
        long discountCents,
        long taxCents,
        long lineTotalCents,
        int refundedQuantity
) {
    public int remainingQuantity() {
        return Math.max(0, quantity - refundedQuantity);
    }
}
