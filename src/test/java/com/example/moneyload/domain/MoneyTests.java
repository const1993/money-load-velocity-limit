package com.example.moneyload.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class MoneyTests {
    @ParameterizedTest
    @CsvSource({"$0.01,1", "$123.45,12345", "$5000.00,500000", "$20000.00,2000000",
            "$0,0", "$0.00,0", "$0.29,29", "$10.000,1000", "$92233720368547758.07,9223372036854775807"})
    void parsesExactly(String input, long cents) {
        assertThat(Money.parse(input).cents()).isEqualTo(cents);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"$10.001", "$abc", "$-10.00", "-$10.00", "10.00", "$",
            "$+10.00", "$1e2", "$ 10.00", "$1,000.00", "$NaN", "$1.2.3", "$1.00 ",
            "$92233720368547758.08", "$999999999999999999999999999",
            " ", " $10.00", "$10.00\n", "$10.00\r\n", "$\t10.00", "$$10.00",
            "$10.", "$.10", "$1E+2", "$0x10", "$1_000.00", "$１０.００",
            "$-0.00", "$0.0001", "$92233720368547758.071"})
    void rejectsInvalidLoadAmounts(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.parse(input));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -100, Long.MIN_VALUE})
    void rejectsNegativeCents(long cents) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(cents))
                .withMessage("Money must not be negative");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.001", "-0.01", "-1", "-92233720368547758.09"})
    void rejectsNegativeDecimals(String amount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.fromDecimal(new BigDecimal(amount)))
                .withMessage("Money must not be negative");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "0.009", "1.001", "92233720368547758.071",
            "92233720368547758.08", "999999999999999999999999999", "1E+100"})
    void rejectsDecimalsThatCannotBeRepresentedAsLongCents(String amount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.fromDecimal(new BigDecimal(amount)))
                .withMessage("Money must contain whole cents and fit in a long")
                .withCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsNullDecimal() {
        assertThatNullPointerException().isThrownBy(() -> Money.fromDecimal(null))
                .withMessage("amount");
    }

    @Test
    void convertsDecimalSumExactly() {
        assertThat(Money.fromDecimal(new BigDecimal("0.10").add(new BigDecimal("0.20"))))
                .isEqualTo(new Money(30));
    }
}
