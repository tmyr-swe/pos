package com.chegg.pos.service;

/** Raised when a guarded stock decrement cannot be satisfied. */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
