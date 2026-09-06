package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.application.LoadFundsService;
import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.domain.LoadAttempt;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.MDC;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ParallelLoadFileProcessor implements LoadFileProcessor {
    private final LoadFundsService service;
    private final FileLoadCodec codec;
    private final int workers;
    private final int windowSize;

    public ParallelLoadFileProcessor(LoadFundsService service, JsonMapper json) {
        this(service, json, 4, 256);
    }

    @Autowired
    public ParallelLoadFileProcessor(LoadFundsService service, JsonMapper json,
                             @Value("${processing.file.workers:4}") int workers,
                             @Value("${processing.file.window-size:256}") int windowSize) {
        if (workers < 1 || windowSize < workers) {
            throw new IllegalArgumentException("File workers must be positive and window-size must be at least workers");
        }
        this.service = service;
        this.codec = new FileLoadCodec(json);
        this.workers = workers;
        this.windowSize = windowSize;
    }

    /** Caller owns the streams. Customer order and global output order are preserved within this upload. */
    public Counts process(BufferedReader input, BufferedWriter output) throws IOException {
        return process(input, output, false);
    }

    public Counts process(BufferedReader input, BufferedWriter output, boolean includeDuplicates) throws IOException {
        long processed = 0;
        var counts = new Counts(0, 0, 0, 0);
        var loggingContext = MDC.getCopyOfContextMap();
        // A bounded window limits parsed input and out-of-order results, even if the first row is slow.
        try (var executor = Executors.newFixedThreadPool(workers)) {
            boolean eof = false;
            while (!eof) {
                var attempts = new ArrayList<LoadAttempt>(windowSize);
                InvalidFileInputException invalid = null;
                for (int i = 0; i < windowSize; i++) {
                    String line = input.readLine();
                    if (line == null) {
                        eof = true;
                        break;
                    }
                    try {
                        attempts.add(codec.read(line, processed + i + 1));
                    } catch (InvalidFileInputException failure) {
                        // Complete the valid prefix, but never submit records beyond the invalid line.
                        invalid = failure;
                        break;
                    }
                }
                var outcomes = new LoadOutcome[attempts.size()];
                var lanes = new ArrayList<List<Integer>>(workers);
                for (int i = 0; i < workers; i++) {
                    lanes.add(new ArrayList<>());
                }
                for (int i = 0; i < attempts.size(); i++) {
                    lanes.get(Math.floorMod(attempts.get(i).customerId().hashCode(), workers)).add(i);
                }
                var failure = new AtomicReference<FileLoadProcessingException>();
                var tasks = new ArrayList<Future<?>>(workers);
                long offset = processed;
                for (var lane : lanes) {
                    if (lane.isEmpty()) {
                        continue;
                    }
                    tasks.add(executor.submit(() -> {
                        if (loggingContext != null) {
                            MDC.setContextMap(loggingContext);
                        }
                        try {
                            for (int index : lane) {
                                if (failure.get() != null) {
                                    break;
                                }
                                try {
                                    outcomes[index] = service.process(attempts.get(index));
                                } catch (RuntimeException cause) {
                                    failure.compareAndSet(null, new FileLoadProcessingException(offset + index + 1, cause));
                                    break;
                                }
                            }
                        } finally {
                            MDC.clear();
                        }
                    }));
                }
                for (var task : tasks) {
                    try {
                        task.get();
                    } catch (InterruptedException interrupted) {
                        tasks.forEach(pending -> pending.cancel(true));
                        Thread.currentThread().interrupt();
                        throw new IOException("File processing interrupted", interrupted);
                    } catch (ExecutionException failed) {
                        throw new IOException("File worker failed", failed.getCause());
                    }
                }
                if (failure.get() != null) {
                    throw failure.get();
                }
                for (var outcome : outcomes) {
                    codec.writeOutcome(output, outcome, includeDuplicates);
                    counts = counts.including(outcome);
                    processed++;
                }
                if (invalid != null) {
                    throw invalid;
                }
            }
        }
        output.flush();
        return counts;
    }

}
