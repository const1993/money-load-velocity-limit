package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.domain.LoadAttempt;
import java.io.BufferedWriter;
import java.io.IOException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class FileLoadCodec {
    private final JsonMapper json;

    FileLoadCodec(JsonMapper json) {
        this.json = json;
    }

    LoadAttempt read(String line, long lineNumber) {
        try {
            return json.readValue(line, FileLoadInput.class).toAttempt();
        } catch (RuntimeException failure) {
            // Do not expose parser messages containing raw input.
            throw new InvalidFileInputException(lineNumber);
        }
    }

    void writeOutcome(BufferedWriter output, LoadOutcome outcome, boolean includeDuplicates) throws IOException {
        switch (outcome) {
            case LoadOutcome.Completed(var result) -> write(output, result);
            case LoadOutcome.Duplicate(var original) -> {
                if (includeDuplicates) write(output, original);
            }
        }
    }

    void write(BufferedWriter output, StoredLoadResult result) throws IOException {
        output.write(json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(
                new FileLoadOutput(result.attempt().loadId(), result.attempt().customerId(), result.decision().accepted())));
        output.write('\n');
    }
}
