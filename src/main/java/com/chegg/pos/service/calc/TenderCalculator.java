package com.chegg.pos.service.calc;

import com.chegg.pos.model.enums.PaymentMethod;
import com.chegg.pos.util.Money;

import java.util.List;

/**
 * Pure tender logic: cash rounding to the nearest KES 1.00, split payment
 * validation and change calculation.
 */
public final class TenderCalculator {

    public static final long CASH_ROUNDING_STEP_CENTS = 100;

    private TenderCalculator() {
    }

    /** Cash amount still needed once card payments are applied, rounded to KES 1.00. */
    public static long cashRequired(long totalCents, long cardAppliedCents) {
        long cashPortion = Math.max(0, totalCents - Math.max(0, cardAppliedCents));
        return Money.roundToNearest(cashPortion, CASH_ROUNDING_STEP_CENTS);
    }

    public static TenderState evaluate(long totalCents, List<TenderPayment> payments) {
        long total = Math.max(0, totalCents);
        long cardApplied = 0;
        long cashApplied = 0;
        long cashTendered = 0;

        if (payments != null) {
            for (TenderPayment payment : payments) {
                if (payment == null) {
                    continue;
                }
                if (payment.method() == PaymentMethod.CARD) {
                    cardApplied += payment.appliedCents();
                } else {
                    cashApplied += payment.appliedCents();
                    cashTendered += payment.tenderedCents();
                }
            }
        }

        if (cardApplied > total) {
            long excess = cardApplied - total;
            return new TenderState(total, 0, 0, total, cardApplied + cashApplied,
                    cashTendered, 0, false,
                    "Card amount exceeds the total by " + Money.format(excess) + ".");
        }

        long cashPortion = total - cardApplied;
        long roundedCash = Money.roundToNearest(cashPortion, CASH_ROUNDING_STEP_CENTS);
        long rounding = roundedCash - cashPortion;
        long payable = total + rounding;
        long applied = cardApplied + cashApplied;

        if (applied > payable) {
            return new TenderState(total, roundedCash, rounding, payable, applied, cashTendered, 0,
                    false, "Payments exceed the total by " + Money.format(applied - payable) + ".");
        }
        if (applied < payable) {
            return new TenderState(total, roundedCash, rounding, payable, applied, cashTendered, 0,
                    false, "Still due " + Money.format(payable - applied) + ".");
        }
        if (cashApplied != roundedCash) {
            return new TenderState(total, roundedCash, rounding, payable, applied, cashTendered, 0,
                    false, "Cash must be " + Money.format(roundedCash) + ".");
        }
        if (cashTendered < cashApplied) {
            return new TenderState(total, roundedCash, rounding, payable, applied, cashTendered, 0,
                    false, "Cash tendered is short by " + Money.format(cashApplied - cashTendered) + ".");
        }
        for (TenderPayment payment : payments) {
            if (payment.method() == PaymentMethod.CARD
                    && payment.tenderedCents() != payment.appliedCents()) {
                return new TenderState(total, roundedCash, rounding, payable, applied, cashTendered, 0,
                        false, "Card tendered must equal the card amount applied.");
            }
        }

        long change = cashTendered - cashApplied;
        return new TenderState(total, roundedCash, rounding, payable, applied, cashTendered, change, true, null);
    }
}
