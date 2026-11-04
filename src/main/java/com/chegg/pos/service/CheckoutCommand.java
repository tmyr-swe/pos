package com.chegg.pos.service;

import com.chegg.pos.model.enums.SaleChannel;
import com.chegg.pos.service.calc.CartSnapshot;
import com.chegg.pos.service.calc.TenderPayment;

import java.util.List;

/** Everything needed to commit a sale atomically. */
public record CheckoutCommand(
        long shiftId,
        SaleChannel channel,
        Long kioskOrderId,
        CartSnapshot cart,
        List<TenderPayment> payments
) {
    public CheckoutCommand {
        payments = List.copyOf(payments == null ? List.of() : payments);
    }
}
