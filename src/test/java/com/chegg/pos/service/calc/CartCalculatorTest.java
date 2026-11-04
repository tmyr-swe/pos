package com.chegg.pos.service.calc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartCalculatorTest {

    private static CartItem item(long id, long price, int quantity, Discount discount) {
        return new CartItem(id, "SKU-" + id, "Item " + id, price, quantity, 0, discount);
    }

    @Test
    void emptyCartHasNoTotals() {
        CartSnapshot snapshot = CartCalculator.calculate(List.of(), Discount.NONE);
        assertTrue(snapshot.isEmpty());
        assertEquals(0, snapshot.totals().totalCents());
    }

    @Test
    void lineAndCartDiscountsAreAppliedOnce() {
        CartSnapshot snapshot = CartCalculator.calculate(
                List.of(item(1, 1_000, 3, Discount.percentPpm(100_000)),
                        item(2, 500, 2, Discount.NONE)),
                Discount.fixedCents(400));

        // Gross 3,000 + 1,000. Line discount 300. Net 3,700. Cart 400 off.
        assertEquals(4_000, snapshot.lines().get(0).grossCents() + snapshot.lines().get(1).grossCents());
        assertEquals(300, snapshot.lines().get(0).lineDiscountCents());
        assertEquals(700, snapshot.totals().discountCents());
        assertEquals(3_300, snapshot.totals().totalCents());
    }

    @Test
    void cartDiscountAllocationAlwaysSumsExactly() {
        List<CartItem> items = List.of(
                item(1, 333, 1, Discount.NONE),
                item(2, 333, 1, Discount.NONE),
                item(3, 334, 1, Discount.NONE));

        CartSnapshot snapshot = CartCalculator.calculate(items, Discount.fixedCents(100));

        long allocated = snapshot.lines().stream()
                .mapToLong(CartLine::cartDiscountCents).sum();
        assertEquals(100, allocated);
        assertEquals(1_000 - 100, snapshot.totals().totalCents());
    }

    @Test
    void largestRemainderHandlesWeightsAndZero() {
        long[] result = CartCalculator.largestRemainder(10, new long[]{1, 1, 1}, 3);
        assertEquals(10, result[0] + result[1] + result[2]);

        long[] none = CartCalculator.largestRemainder(0, new long[]{5, 5}, 10);
        assertEquals(0, none[0]);
        assertEquals(0, none[1]);
    }

    @Test
    void taxIsZeroWhileVatIsDisabled() {
        CartSnapshot snapshot = CartCalculator.calculate(
                List.of(new CartItem(1, "A", "A", 10_000, 1, 0, Discount.NONE)),
                Discount.NONE);
        assertEquals(0, snapshot.totals().taxCents());
        assertEquals(10_000, snapshot.totals().totalCents());
    }
}
