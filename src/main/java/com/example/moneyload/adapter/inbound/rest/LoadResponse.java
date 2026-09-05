package com.example.moneyload.adapter.inbound.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"id", "customer_id", "accepted"})
public record LoadResponse(String id, @JsonProperty("customer_id") String customerId, boolean accepted) { }
