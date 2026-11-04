package com.chegg.pos.service.calc;

import java.util.List;

/** Immutable view of a cart published on every change. */
public record CartSnapshot(List<CartLine> lines, CartTotals totals, Discount cartDiscount) {

    public CartSnapshot {
        lines = List.copyOf(lines);
    }

    public static CartSnapshot empty() {
        return new CartSnapshot(List.of(), new CartTotals(0, 0, 0, 0), Discount.NONE);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int itemCount() {
        return lines.stream().mapToInt(CartLine::quantity).sum();
    }
}
