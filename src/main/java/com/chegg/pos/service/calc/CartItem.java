package com.chegg.pos.service.calc;

/** A priced cart line before discount/tax calculation. */
public record CartItem(
        long productId,
        String sku,
        String name,
        long unitPriceCents,
        int quantity,
        long taxRatePpm,
        Discount lineDiscount
) {
    public CartItem {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }
        if (unitPriceCents < 0) {
            throw new IllegalArgumentException("Unit price cannot be negative.");
        }
        if (taxRatePpm < 0) {
            throw new IllegalArgumentException("Tax rate cannot be negative.");
        }
        if (lineDiscount == null) {
            lineDiscount = Discount.NONE;
        }
    }

    public long grossCents() {
        return unitPriceCents * quantity;
    }
}
