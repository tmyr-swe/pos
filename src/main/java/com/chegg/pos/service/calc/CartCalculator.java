package com.chegg.pos.service.calc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The single pricing pipeline. No UI or JDBC class performs arithmetic.
 */
public final class CartCalculator {

    private CartCalculator() {
    }

    public static CartSnapshot calculate(List<CartItem> items, Discount cartDiscount) {
        if (items == null || items.isEmpty()) {
            return CartSnapshot.empty();
        }
        Discount cart = cartDiscount == null ? Discount.NONE : cartDiscount;

        long grossSum = 0;
        long lineDiscountSum = 0;
        long netSum = 0;
        long[] gross = new long[items.size()];
        long[] lineDiscounts = new long[items.size()];
        long[] nets = new long[items.size()];

        for (int i = 0; i < items.size(); i++) {
            CartItem item = items.get(i);
            gross[i] = item.grossCents();
            lineDiscounts[i] = item.lineDiscount().applyTo(gross[i]);
            nets[i] = gross[i] - lineDiscounts[i];
            grossSum += gross[i];
            lineDiscountSum += lineDiscounts[i];
            netSum += nets[i];
        }

        long cartDiscountCents = cart.applyTo(netSum);
        long[] allocations = largestRemainder(cartDiscountCents, nets, netSum);

        List<CartLine> lines = new ArrayList<>(items.size());
        long taxSum = 0;
        long totalSum = 0;

        for (int i = 0; i < items.size(); i++) {
            CartItem item = items.get(i);
            long taxable = nets[i] - allocations[i];
            long tax = TaxEngine.taxOnExclusive(taxable, item.taxRatePpm());
            long total = taxable + tax;
            taxSum += tax;
            totalSum += total;
            lines.add(new CartLine(
                    item.productId(),
                    item.sku(),
                    item.name(),
                    item.unitPriceCents(),
                    item.quantity(),
                    gross[i],
                    lineDiscounts[i],
                    allocations[i],
                    taxable,
                    item.taxRatePpm(),
                    tax,
                    total
            ));
        }

        CartTotals totals = new CartTotals(netSum, lineDiscountSum + cartDiscountCents, taxSum, totalSum);
        CartSnapshot snapshot = new CartSnapshot(lines, totals, cart);

        assert snapshot.totals().totalCents() == grossSum - lineDiscountSum - cartDiscountCents + taxSum
                : "pricing pipeline lost cents";
        return snapshot;
    }

    /**
     * Distributes {@code amount} pro-rata by weight with largest-remainder
     * correction. The returned allocations always sum exactly to amount.
     */
    static long[] largestRemainder(long amount, long[] weights, long weightSum) {
        long[] result = new long[weights.length];
        if (amount <= 0 || weightSum <= 0) {
            return result;
        }

        record Remainder(int index, long fraction) {
        }

        List<Remainder> remainders = new ArrayList<>(weights.length);
        long allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            long numerator = amount * weights[i];
            result[i] = numerator / weightSum;
            long fraction = numerator % weightSum;
            allocated += result[i];
            remainders.add(new Remainder(i, fraction));
        }

        long leftover = amount - allocated;
        remainders.sort(Comparator
                .comparingLong(Remainder::fraction).reversed()
                .thenComparingInt(Remainder::index));
        for (int i = 0; i < leftover && i < remainders.size(); i++) {
            result[remainders.get(i).index()]++;
        }
        return result;
    }
}
