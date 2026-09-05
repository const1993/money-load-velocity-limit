package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.application.LoadFundsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoadController {
    private final LoadFundsService service;

    public LoadController(LoadFundsService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/loads", consumes = "application/json", produces = "application/json")
    public LoadResponse process(@RequestBody LoadRequest request) {
        var outcome = service.process(request.toAttempt());
        var stored = switch (outcome) {
            case LoadOutcome.Completed completed -> completed.result();
            case LoadOutcome.Duplicate duplicate -> duplicate.originalResult();
        };
        return new LoadResponse(stored.attempt().loadId(), stored.attempt().customerId(), stored.decision().accepted());
    }
}
