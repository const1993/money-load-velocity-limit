package com.example.moneyload.adapter.inbound.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"id", "customer_id", "accepted"})
public record FileLoadOutput(String id, @JsonProperty("customer_id") String customerId, boolean accepted) { }
