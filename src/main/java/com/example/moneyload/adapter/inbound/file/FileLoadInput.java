package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.Money;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record FileLoadInput(String id, @JsonProperty("customer_id") String customerId,
                            @JsonProperty("load_amount") String loadAmount, String time) {
    LoadAttempt toAttempt() {
        return new LoadAttempt(id, customerId, Money.parse(loadAmount), OffsetDateTime.parse(time).toInstant());
    }
}
