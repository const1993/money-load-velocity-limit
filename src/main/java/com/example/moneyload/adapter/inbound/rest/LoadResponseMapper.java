package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LoadResponseMapper {
    default LoadResponse toResponse(LoadOutcome outcome) {
        var stored = switch (outcome) {
            case LoadOutcome.Completed(var result) -> result;
            case LoadOutcome.Duplicate(var original) -> original;
        };
        return fromStoredResult(stored);
    }

    @Mapping(target = "id", source = "attempt.loadId")
    @Mapping(target = "customerId", source = "attempt.customerId")
    @Mapping(target = "accepted", expression = "java(result.decision().accepted())")
    LoadResponse fromStoredResult(StoredLoadResult result);
}
