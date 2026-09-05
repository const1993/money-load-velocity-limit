package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.Money;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record LoadRequest(String id, @JsonProperty("customer_id") String customerId,
                          @JsonProperty("load_amount") String loadAmount, String time) {
    LoadAttempt toAttempt() {
        try {
            return new LoadAttempt(id, customerId, Money.parse(loadAmount), OffsetDateTime.parse(time).toInstant());
        } catch (IllegalArgumentException | java.time.DateTimeException | NullPointerException failure) {
            // Only mapping failures are client errors; exceptions from the service remain technical failures.
            throw new InvalidLoadRequestException(failure);
        }
    }
}
