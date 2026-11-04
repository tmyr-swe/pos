package com.chegg.pos.service.calc;

/** Result of evaluating a set of tenders against a total. */
public record TenderState(
        long totalDueCents,
        long roundedCashCents,
        long roundingCents,
        long payableCents,
        long appliedCents,
        long tenderedCashCents,
        long changeCents,
        boolean valid,
        String error
) {
    public static TenderState invalid(long totalDue, long rounding, long payable,
                                      long applied, long cashTendered, String error) {
        return new TenderState(totalDue, Math.max(0, payable - rounding), rounding, payable,
                applied, cashTendered, 0, false, error);
    }
}
