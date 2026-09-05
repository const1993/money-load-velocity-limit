package com.example.moneyload.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Money(long cents) {
    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("Money must not be negative");
        }
    }

    public static Money parse(String value) {
        if (value == null || !value.matches("\\$[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("Load amount must be a $-prefixed decimal amount");
        }
        return fromDecimal(new BigDecimal(value.substring(1)));
    }

    public static Money fromDecimal(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money must not be negative");
        }
        try {
            return new Money(amount.movePointRight(2).longValueExact());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Money must contain whole cents and fit in a long", exception);
        }
    }
}
