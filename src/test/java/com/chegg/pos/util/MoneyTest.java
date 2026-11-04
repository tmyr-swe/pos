package com.chegg.pos.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void formatsCoinsAndGrouping() {
        assertEquals("KSh 0.00", Money.format(0));
        assertEquals("KSh 0.05", Money.format(5));
        assertEquals("KSh 12.34", Money.format(1234));
        assertEquals("KSh 1,250.00", Money.format(125_000));
        assertEquals("KSh 1,234,567.89", Money.format(123_456_789));
    }

    @Test
    void formatsNegativeValuesForAdjustments() {
        assertEquals("-KSh 5.00", Money.format(-500));
    }

    @Test
    void parsesPlainAndPrefixedAmounts() {
        assertEquals(0, Money.parse("0"));
        assertEquals(123_450, Money.parse("1,234.50"));
        assertEquals(125_000, Money.parse("KSh 1,250"));
        assertEquals(125_000, Money.parse("KES 1250.00"));
        assertEquals(5, Money.parse("0.05"));
    }

    @Test
    void rejectsMoreThanTwoDecimalPlaces() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1.234"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("not money"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse(" "));
    }

    @Test
    void roundsHalfUpToTheNearestStep() {
        assertEquals(1_200, Money.roundToNearest(1_249, 100));
        assertEquals(1_300, Money.roundToNearest(1_250, 100));
        assertEquals(1_300, Money.roundToNearest(1_251, 100));
    }
}
