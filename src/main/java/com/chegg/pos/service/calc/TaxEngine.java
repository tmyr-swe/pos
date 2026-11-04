package com.chegg.pos.service.calc;

/** Tax arithmetic in integer cents with parts-per-million rates. */
public final class TaxEngine {

    private static final long PPM = 1_000_000L;

    private TaxEngine() {
    }

    /** Tax on a tax-exclusive amount, rounded half-up. 16% = 160000 ppm. */
    public static long taxOnExclusive(long cents, long ratePpm) {
        if (cents <= 0 || ratePpm <= 0) {
            return 0;
        }
        return (cents * ratePpm + PPM / 2) / PPM;
    }

    /** Tax contained in a tax-inclusive amount. 16% = 160000 ppm. */
    public static long taxInInclusive(long grossCents, long ratePpm) {
        if (grossCents <= 0 || ratePpm <= 0) {
            return 0;
        }
        long divisor = PPM + ratePpm;
        return (grossCents * ratePpm + divisor / 2) / divisor;
    }

    /** Applies the configured default rate unless the per-line rate is set. */
    public static long effectiveRate(long lineRatePpm, long defaultRatePpm) {
        return lineRatePpm > 0 ? lineRatePpm : Math.max(defaultRatePpm, 0);
    }
}
