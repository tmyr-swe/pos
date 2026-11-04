package com.chegg.pos.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Kenyan shilling formatting and parsing. All stored amounts are integer
 * cents; formatting and parsing happen only at the UI/PDF boundary.
 */
public final class Money {

    public static final String SYMBOL = "KSh";
    public static final Locale LOCALE = Locale.of("en", "KE");

    private static final ThreadLocal<NumberFormat> FORMAT = ThreadLocal.withInitial(() -> {
        NumberFormat format = NumberFormat.getNumberInstance(LOCALE);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        format.setGroupingUsed(true);
        return format;
    });

    private Money() {
    }

    /** 125000 -> "KSh 1,250.00" */
    public static String format(long cents) {
        BigDecimal amount = BigDecimal.valueOf(cents, 2);
        String sign = amount.signum() < 0 ? "-" : "";
        return sign + SYMBOL + " " + FORMAT.get().format(amount.abs());
    }

    /** 125000 -> "1,250.00" (no currency symbol, useful for PDF columns). */
    public static String formatPlain(long cents) {
        BigDecimal amount = BigDecimal.valueOf(cents, 2);
        String sign = amount.signum() < 0 ? "-" : "";
        return sign + FORMAT.get().format(amount.abs());
    }

    /**
     * Parses user input. Accepts "1250", "1,250", "KSh 1,250.50",
     * "KES 1250", surrounding whitespace and a leading minus sign.
     * More than two decimal places is rejected rather than silently rounded.
     */
    public static long parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Enter an amount.");
        }
        String text = input.trim()
                .replace("\u00A0", "")
                .replace(" ", "");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Enter an amount.");
        }

        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.startsWith("KSHS")) {
            text = text.substring(4);
        } else if (upper.startsWith("KSH")) {
            text = text.substring(3);
        } else if (upper.startsWith("KES")) {
            text = text.substring(3);
        }
        text = text.replace(",", "").trim();
        if (text.isEmpty() || text.equals("-") || text.equals("+")) {
            throw new IllegalArgumentException("Enter a valid amount.");
        }

        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + input.trim() + "' is not a valid amount.");
        }
        if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amounts may have at most two decimal places.");
        }
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Rounds half-up to the nearest step (for KES 1.00 cash rounding use 100). */
    public static long roundToNearest(long cents, long step) {
        if (step <= 0) {
            throw new IllegalArgumentException("Step must be positive.");
        }
        return Math.floorDiv(cents + step / 2, step) * step;
    }

    /** Percentage in parts-per-million to a readable percent. */
    public static String formatPercent(long ppm) {
        return BigDecimal.valueOf(ppm, 4).stripTrailingZeros().toPlainString() + "%";
    }
}
