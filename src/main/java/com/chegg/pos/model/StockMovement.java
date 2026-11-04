package com.chegg.pos.model;

import java.time.Instant;

public record StockMovement(
        long id,
        long productId,
        String productName,
        int delta,
        String reason,
        String referenceType,
        Long referenceId,
        String userName,
        Instant createdAt
) {
}
