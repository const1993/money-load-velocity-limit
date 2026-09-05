package com.example.moneyload.adapter.inbound.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiError(String code, String message, @JsonProperty("request_id") String requestId) { }
