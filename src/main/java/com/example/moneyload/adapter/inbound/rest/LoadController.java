package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.application.LoadFundsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoadController {
    private final LoadFundsService service;
    private final LoadResponseMapper mapper;

    public LoadController(LoadFundsService service, LoadResponseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(path = "/v1/loads", consumes = "application/json", produces = "application/json")
    public LoadResponse process(@RequestBody LoadRequest request) {
        var outcome = service.process(request.toAttempt());
        return mapper.toResponse(outcome);
    }
}
