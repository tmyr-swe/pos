package com.chegg.pos.model;

import com.chegg.pos.model.enums.KioskOrderStatus;

import java.time.Instant;
import java.util.List;

public record KioskOrder(
        long id,
        String code,
        KioskOrderStatus status,
        Instant createdAt,
        Instant expiresAt,
        Long completedSaleId,
        long subtotalCents,
        long totalCents,
        List<Item> items
) {
    public record Item(long id, long productId, String name, long unitPriceCents, int quantity) {
        public long lineTotalCents() {
            return unitPriceCents * quantity;
        }
    }
}
