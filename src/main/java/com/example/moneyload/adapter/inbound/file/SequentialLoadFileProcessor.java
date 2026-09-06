package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.application.LoadFundsService;
import com.example.moneyload.application.LoadOutcome;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SequentialLoadFileProcessor implements LoadFileProcessor {
    private final LoadFundsService service;
    private final FileLoadCodec codec;

    public SequentialLoadFileProcessor(LoadFundsService service, JsonMapper json) {
        this.service = service;
        this.codec = new FileLoadCodec(json);
    }

    @Override
    public Counts process(BufferedReader input, BufferedWriter output, boolean includeDuplicates) throws IOException {
        long processed = 0;
        var counts = new Counts(0, 0, 0, 0);
        String line;
        while ((line = input.readLine()) != null) {
            var attempt = codec.read(line, processed + 1);
            LoadOutcome outcome;
            try {
                outcome = service.process(attempt);
            } catch (RuntimeException failure) {
                throw new FileLoadProcessingException(processed + 1, failure);
            }
            codec.writeOutcome(output, outcome, includeDuplicates);
            counts = counts.including(outcome);
            processed++;
        }
        output.flush();
        return counts;
    }
}
