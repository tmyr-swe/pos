package com.chegg.pos.model;

import java.time.Instant;

public record AuditEntry(
        long id,
        Instant at,
        Long userId,
        String userName,
        Long shiftId,
        String action,
        String entityType,
        Long entityId,
        String summary,
        String details
) {
}
