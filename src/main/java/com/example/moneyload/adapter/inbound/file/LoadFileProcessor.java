package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.application.LoadFundsService;
import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LoadFileProcessor {
    private final LoadFundsService service;
    private final JsonMapper json;

    public LoadFileProcessor(LoadFundsService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    /** Streams in order; the caller owns and closes the reader and writer. */
    public Counts process(BufferedReader input, BufferedWriter output) throws IOException {
        return process(input, output, false);
    }

    public Counts process(BufferedReader input, BufferedWriter output, boolean includeDuplicates) throws IOException {
        long processed = 0, accepted = 0, declined = 0, duplicates = 0;
        String line;
        while ((line = input.readLine()) != null) {
            long lineNumber = processed + 1;
            LoadAttempt attempt;
            try {
                attempt = json.readValue(line, FileLoadInput.class).toAttempt();
            } catch (RuntimeException failure) {
                // Parser messages can contain raw input; report only the location.
                throw new InvalidFileInputException(lineNumber);
            }
            LoadOutcome outcome;
            try {
                outcome = service.process(attempt);
            } catch (RuntimeException failure) {
                throw new FileLoadProcessingException(lineNumber, failure);
            }
            if (outcome instanceof LoadOutcome.Completed completed) {
                writeResult(output, completed.result());
                if (completed.accepted()) {
                    accepted++;
                } else {
                    declined++;
                }
            } else {
                duplicates++;
                if (includeDuplicates && outcome instanceof LoadOutcome.Duplicate duplicate) {
                    writeResult(output, duplicate.originalResult());
                }
            }
            processed++;
        }
        output.flush();
        return new Counts(processed, accepted, declined, duplicates);
    }

    private void writeResult(BufferedWriter output, StoredLoadResult result) throws IOException {
        output.write(json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(
                new FileLoadOutput(result.attempt().loadId(), result.attempt().customerId(), result.decision().accepted())));
        output.write('\n');
    }

    public record Counts(long processed, long accepted, long declined, long duplicates) { }
}
