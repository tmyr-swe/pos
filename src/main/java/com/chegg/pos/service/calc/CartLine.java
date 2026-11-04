package com.chegg.pos.service.calc;

/** One fully calculated sale line, suitable for persistence and display. */
public record CartLine(
        long productId,
        String sku,
        String name,
        long unitPriceCents,
        int quantity,
        long grossCents,
        long lineDiscountCents,
        long cartDiscountCents,
        long taxableCents,
        long taxRatePpm,
        long taxCents,
        long totalCents
) {
}
