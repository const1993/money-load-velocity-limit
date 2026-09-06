package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.adapter.inbound.file.InvalidFileInputException;
import com.example.moneyload.adapter.inbound.file.FileLoadProcessingException;
import com.example.moneyload.adapter.inbound.file.LoadFileProcessor;
import com.example.moneyload.adapter.inbound.file.SequentialLoadFileProcessor;
import com.example.moneyload.adapter.inbound.file.ParallelLoadFileProcessor;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessResourceException;
import java.util.concurrent.Semaphore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class LoadFileController {
    private static final Logger log = LoggerFactory.getLogger(LoadFileController.class);
    private final Semaphore uploads;
    private final Duration timeout;
    private final SequentialLoadFileProcessor sequential;
    private final ParallelLoadFileProcessor parallel;

    public LoadFileController(SequentialLoadFileProcessor sequential, ParallelLoadFileProcessor parallel) {
        this(sequential, parallel, 2, Duration.ofMinutes(5));
    }

    @Autowired
    public LoadFileController(SequentialLoadFileProcessor sequential, ParallelLoadFileProcessor parallel,
                              @Value("${processing.file.max-concurrent-uploads:2}") int maxUploads,
                              @Value("${processing.file.timeout:5m}") Duration timeout) {
        if (maxUploads < 1 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Upload concurrency and timeout must be positive");
        }
        this.uploads = new Semaphore(maxUploads);
        this.timeout = timeout;
        this.sequential = sequential;
        this.parallel = parallel;
    }

    @PostMapping(path = "/v1/loads/file/sequential", consumes = "multipart/form-data", produces = "application/x-ndjson")
    public void sequential(@RequestParam("file") MultipartFile file,
                           @RequestParam(name = "includeDuplicates", defaultValue = "false") boolean includeDuplicates,
                           @RequestParam(name = "download", defaultValue = "false") boolean download,
                           HttpServletResponse response) throws Exception {
        process(sequential, file, response, download, includeDuplicates);
    }

    @PostMapping(path = "/v1/loads/file/parallel", consumes = "multipart/form-data", produces = "application/x-ndjson")
    public void parallel(@RequestParam("file") MultipartFile file,
                         @RequestParam(name = "includeDuplicates", defaultValue = "false") boolean includeDuplicates,
                         @RequestParam(name = "download", defaultValue = "false") boolean download,
                         HttpServletResponse response) throws Exception {
        process(parallel, file, response, download, includeDuplicates);
    }

    private void process(LoadFileProcessor processor, MultipartFile file, HttpServletResponse response,
                         boolean download, boolean includeDuplicates) throws Exception {
        if (!uploads.tryAcquire()) {
            throw new TransientDataAccessResourceException("Upload processing capacity exhausted");
        }
        long started = System.nanoTime();
        String mode = processor == sequential ? "sequential" : "parallel";
        java.nio.file.Path output = null;
        log.atInfo().addKeyValue("event", "file_processing_started").addKeyValue("mode", mode)
                .addKeyValue("input_bytes", file.getSize()).log("File processing started");
        try {
            output = Files.createTempFile("money-load-output-", ".jsonl");
            LoadFileProcessor.Counts counts;
            try (var reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
                @Override public String readLine() throws java.io.IOException {
                    if (System.nanoTime() - started >= timeout.toNanos()) {
                        throw new TransientDataAccessResourceException("File processing deadline exceeded");
                    }
                    return super.readLine();
                }
            }; var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                counts = processor.process(reader, writer, includeDuplicates);
            }
            response.setContentType("application/x-ndjson");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            if (download) {
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"output.txt\"");
            }
            response.setContentLengthLong(Files.size(output));
            Files.copy(output, response.getOutputStream());
            log.atInfo().addKeyValue("event", "file_processing_completed").addKeyValue("mode", mode)
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .addKeyValue("processed", counts.processed()).addKeyValue("accepted", counts.accepted())
                    .addKeyValue("declined", counts.declined()).addKeyValue("duplicates", counts.duplicates())
                    .log("File processing completed");
        } catch (Exception failure) {
            var event = log.atInfo().addKeyValue("event", "file_processing_failed").addKeyValue("mode", mode)
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000);
            if (failure instanceof InvalidFileInputException invalid) event = event.addKeyValue("line_number", invalid.lineNumber());
            if (failure instanceof FileLoadProcessingException technical) event = event.addKeyValue("line_number", technical.lineNumber());
            event.log("File processing stopped");
            throw failure;
        } finally {
            try {
                if (output != null) Files.deleteIfExists(output);
            } finally {
                uploads.release();
            }
        }
    }
}
