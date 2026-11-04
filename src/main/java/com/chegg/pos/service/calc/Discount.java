package com.chegg.pos.service.calc;

/**
 * An immutable discount. PERCENT values are parts-per-million (10% = 100000);
 * FIXED values are cents.
 */
public record Discount(DiscountType type, long value) {

    public static final Discount NONE = new Discount(DiscountType.NONE, 0);
    public static final long FULL_PERCENT_PPM = 1_000_000L;

    public Discount {
        if (type == null) {
            throw new IllegalArgumentException("Discount type is required.");
        }
        if (value < 0) {
            throw new IllegalArgumentException("Discount cannot be negative.");
        }
        if (type == DiscountType.PERCENT && value > FULL_PERCENT_PPM) {
            throw new IllegalArgumentException("Percentage discount cannot exceed 100%.");
        }
        if (type == DiscountType.NONE && value != 0) {
            throw new IllegalArgumentException("A NONE discount must have value 0.");
        }
    }

    public static Discount percentPpm(long ppm) {
        return new Discount(DiscountType.PERCENT, ppm);
    }

    public static Discount fixedCents(long cents) {
        return new Discount(DiscountType.FIXED, cents);
    }

    public boolean isNone() {
        return type == DiscountType.NONE || value == 0;
    }

    /** Amount to take off {@code baseCents}, never exceeding the base. */
    public long applyTo(long baseCents) {
        if (baseCents <= 0 || isNone()) {
            return 0;
        }
        long amount = switch (type) {
            case NONE -> 0;
            case FIXED -> value;
            case PERCENT -> (baseCents * value + FULL_PERCENT_PPM / 2) / FULL_PERCENT_PPM;
        };
        return Math.min(Math.max(amount, 0), baseCents);
    }

    public static Discount percentFromInput(String text) {
        String value = text == null ? "" : text.trim().replace("%", "");
        if (value.isEmpty()) {
            return NONE;
        }
        return percentPpm(new java.math.BigDecimal(value)
                .movePointRight(4)
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact());
    }
}
