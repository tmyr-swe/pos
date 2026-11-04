package com.chegg.pos.model;

import java.time.Instant;

/** Immutable Z-report snapshot. The rendered text is stored with it. */
public record ZReport(
        long id,
        long shiftId,
        Instant generatedAt,
        long grossCents,
        long discountsCents,
        long netCents,
        long taxCents,
        long cashCents,
        long cardCents,
        long roundingCents,
        long refundsCents,
        int refundCount,
        int voidCount,
        int txnCount,
        long openingCashCents,
        long expectedCashCents,
        long countedCashCents,
        long varianceCents,
        String reportText
) {
    public long averageTicketCents() {
        return txnCount == 0 ? 0 : Math.round((double) netCents / txnCount);
    }
}
