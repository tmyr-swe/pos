package com.chegg.pos.service.calc;

import com.chegg.pos.model.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenderCalculatorTest {

    @Test
    void cashRoundsToTheNearestShilling() {
        assertEquals(1_200, TenderCalculator.cashRequired(1_249, 0));
        assertEquals(1_300, TenderCalculator.cashRequired(1_250, 0));
        assertEquals(0, TenderCalculator.cashRequired(40, 40));
    }

    @Test
    void roundedCashProducesNoChange() {
        TenderState state = TenderCalculator.evaluate(1_250,
                List.of(TenderPayment.cash(1_300, 1_300)));

        assertTrue(state.valid(), state.error());
        assertEquals(50, state.roundingCents());
        assertEquals(1_300, state.payableCents());
        assertEquals(0, state.changeCents());
    }

    @Test
    void changeIsCashTenderedMinusCashApplied() {
        TenderState state = TenderCalculator.evaluate(1_250,
                List.of(TenderPayment.cash(1_300, 2_000)));

        assertTrue(state.valid(), state.error());
        assertEquals(700, state.changeCents());
    }

    @Test
    void cardPlusCashMustCoverTheRoundedCashPortion() {
        long total = 1_250;
        long cash = TenderCalculator.cashRequired(total, 1_000);
        TenderState state = TenderCalculator.evaluate(total, List.of(
                TenderPayment.card(1_000, null),
                TenderPayment.cash(cash, cash)));

        assertTrue(state.valid(), state.error());
        assertEquals(1_300, state.payableCents());
        assertEquals(1_300, state.appliedCents());
        assertEquals(50, state.roundingCents());
    }

    @Test
    void underpaymentAndOverpaymentAreRejected() {
        TenderState shortPaid = TenderCalculator.evaluate(1_000,
                List.of(TenderPayment.cash(900, 900)));
        assertFalse(shortPaid.valid());
        assertTrue(shortPaid.error().contains("Still due"), shortPaid.error());

        TenderState overpaid = TenderCalculator.evaluate(1_000,
                List.of(TenderPayment.cash(1_000, 1_000),
                        TenderPayment.card(100, null)));
        assertFalse(overpaid.valid());
        assertTrue(overpaid.error().contains("exceed"), overpaid.error());
    }

    @Test
    void cardTenderedMustMatchCardApplied() {
        TenderState state = TenderCalculator.evaluate(1_000, List.of(
                new TenderPayment(PaymentMethod.CARD, 1_000, 900, "TX")));
        assertFalse(state.valid());
        assertTrue(state.error().contains("Card tendered"), state.error());
    }
}
