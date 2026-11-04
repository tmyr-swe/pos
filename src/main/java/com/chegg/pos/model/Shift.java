package com.chegg.pos.model;

import com.chegg.pos.model.enums.ShiftStatus;

import java.time.Instant;

public record Shift(
        long id,
        String terminalId,
        long openedBy,
        String openedByName,
        Instant openedAt,
        long openingCashCents,
        Long closedBy,
        Instant closedAt,
        Long countedCashCents,
        Long expectedCashCents,
        Long varianceCents,
        ShiftStatus status,
        String notes
) {
    public boolean isOpen() {
        return status == ShiftStatus.OPEN;
    }
}
