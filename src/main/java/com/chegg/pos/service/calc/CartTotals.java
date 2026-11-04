package com.chegg.pos.service.calc;

/** Aggregated totals for a cart. */
public record CartTotals(
        long subtotalCents,
        long discountCents,
        long taxCents,
        long totalCents
) {
}
