package com.chegg.pos.service;

import com.chegg.pos.service.calc.CartCalculator;
import com.chegg.pos.service.calc.CartItem;
import com.chegg.pos.service.calc.CartLine;
import com.chegg.pos.service.calc.CartSnapshot;
import com.chegg.pos.service.calc.Discount;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * In-memory, EDT-confined cart. Every mutation publishes one immutable
 * snapshot. Pricing is delegated entirely to CartCalculator.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class CartService {

    public static final int MAX_QUANTITY = 999;

    private final LinkedHashMap<Long, CartItem> items = new LinkedHashMap<>();
    private final List<Consumer<CartSnapshot>> listeners = new ArrayList<>();
    private final JdbcTemplate jdbc;
    private final SessionService sessions;

    private Discount cartDiscount = Discount.NONE;
    private Long kioskOrderId;

    public CartService(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    public void addListener(Consumer<CartSnapshot> listener) {
        listeners.add(listener);
    }

    public void add(CatalogService.ProductRow product) {
        if (product == null) {
            return;
        }
        CartItem existing = items.get(product.id());
        int quantity = existing == null ? 1 : existing.quantity() + 1;
        if (quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Maximum quantity is " + MAX_QUANTITY + ".");
        }
        if (existing == null) {
            items.put(product.id(), new CartItem(
                    product.id(), product.sku(), product.name(), product.priceCents(),
                    quantity, product.taxRatePpm(), Discount.NONE));
        } else {
            items.put(product.id(), withQuantity(existing, quantity));
        }
        changed();
    }

    public void addLines(List<CartLine> lines) {
        for (CartLine line : lines) {
            items.put(line.productId(), new CartItem(
                    line.productId(), line.sku(), line.name(), line.unitPriceCents(),
                    Math.min(MAX_QUANTITY, line.quantity()), line.taxRatePpm(), Discount.NONE));
        }
        changed();
    }

    public void setQuantity(long productId, int quantity) {
        CartItem item = items.get(productId);
        if (item == null) {
            return;
        }
        if (quantity <= 0) {
            items.remove(productId);
        } else {
            items.put(productId, withQuantity(item, Math.min(quantity, MAX_QUANTITY)));
        }
        changed();
    }

    public void increment(long productId) {
        CartItem item = items.get(productId);
        if (item != null) {
            setQuantity(productId, item.quantity() + 1);
        }
    }

    public void decrement(long productId) {
        CartItem item = items.get(productId);
        if (item != null) {
            setQuantity(productId, item.quantity() - 1);
        }
    }

    public void remove(long productId) {
        if (items.remove(productId) != null) {
            changed();
        }
    }

    public void clear() {
        items.clear();
        cartDiscount = Discount.NONE;
        kioskOrderId = null;
        changed();
    }

    public void applyLineDiscount(long productId, Discount discount) {
        CartItem item = items.get(productId);
        if (item == null) {
            return;
        }
        requireDiscountAllowed(discount, item.grossCents());
        items.put(productId, new CartItem(item.productId(), item.sku(), item.name(),
                item.unitPriceCents(), item.quantity(), item.taxRatePpm(), discount));
        changed();
    }

    public void applyCartDiscount(Discount discount) {
        requireDiscountAllowed(discount, snapshot().totals().subtotalCents());
        cartDiscount = discount;
        changed();
    }

    public CartSnapshot snapshot() {
        return CartCalculator.calculate(List.copyOf(items.values()), cartDiscount);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Long kioskOrderId() {
        return kioskOrderId;
    }

    public void setKioskOrderId(Long kioskOrderId) {
        this.kioskOrderId = kioskOrderId;
    }

    private void requireDiscountAllowed(Discount discount, long base) {
        if (discount == null || discount.isNone()) {
            return;
        }
        if (!sessions.has("SALE_DISCOUNT")) {
            throw new SecurityException("You do not have permission to discount sales.");
        }
        Long cap = jdbc.queryForObject("""
                SELECT r.max_discount_ppm FROM roles r
                JOIN users u ON u.role_id = r.id
                WHERE u.id = ?
                """, Long.class, sessions.requireUser());
        long capPpm = cap == null ? 0 : cap;
        long requestedPpm = discount.type() == com.chegg.pos.service.calc.DiscountType.PERCENT
                ? discount.value()
                : (base <= 0 ? 0 : discount.value() * 1_000_000L / base);
        if (requestedPpm > capPpm) {
            throw new IllegalArgumentException("Your role limits discounts to "
                    + com.chegg.pos.util.Money.formatPercent(capPpm) + ".");
        }
    }

    private static CartItem withQuantity(CartItem item, int quantity) {
        return new CartItem(item.productId(), item.sku(), item.name(),
                item.unitPriceCents(), quantity, item.taxRatePpm(), item.lineDiscount());
    }

    private void changed() {
        CartSnapshot snapshot = snapshot();
        for (Consumer<CartSnapshot> listener : List.copyOf(listeners)) {
            listener.accept(snapshot);
        }
    }

    public Map<Long, CartItem> items() {
        return Map.copyOf(items);
    }
}
