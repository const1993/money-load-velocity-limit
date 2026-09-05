package com.example.moneyload.adapter.inbound.rest;

final class InvalidLoadRequestException extends RuntimeException {
    InvalidLoadRequestException(RuntimeException cause) {
        super("Invalid load request", cause);
    }
}
