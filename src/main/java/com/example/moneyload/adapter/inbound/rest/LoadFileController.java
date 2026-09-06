package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.adapter.inbound.file.InvalidFileInputException;
import com.example.moneyload.adapter.inbound.file.FileLoadProcessingException;
import com.example.moneyload.adapter.inbound.file.LoadFileProcessor;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class LoadFileController {
    private final LoadFileProcessor processor;

    public LoadFileController(LoadFileProcessor processor) {
        this.processor = processor;
    }

    @PostMapping(path = "/v1/loads/file/download", consumes = "multipart/form-data", produces = "application/x-ndjson")
    public void download(@RequestParam("file") MultipartFile file,
                         @RequestParam(name = "includeDuplicates", defaultValue = "false") boolean includeDuplicates,
                         HttpServletResponse response) throws Exception {
        process(file, response, true, includeDuplicates);
    }

    @PostMapping(path = "/v1/loads/file", consumes = "multipart/form-data", produces = "application/x-ndjson")
    public void body(@RequestParam("file") MultipartFile file,
                         @RequestParam(name = "includeDuplicates", defaultValue = "false") boolean includeDuplicates,
                         HttpServletResponse response) throws Exception {
        process(file, response, false, includeDuplicates);
    }

    private void process(MultipartFile file, HttpServletResponse response, boolean download, boolean includeDuplicates) throws Exception {
        // Finish processing before committing HTTP headers, with bounded memory.
        var output = Files.createTempFile("money-load-output-", ".jsonl");
        try {
            try (var reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
                 var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                processor.process(reader, writer, includeDuplicates);
            } catch (InvalidFileInputException failure) {
                throw new InvalidLoadRequestException(failure);
            } catch (FileLoadProcessingException failure) {
                throw failure.serviceFailure();
            }
            response.setContentType("application/x-ndjson");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            if (download) {
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"output.txt\"");
            }
            response.setContentLengthLong(Files.size(output));
            Files.copy(output, response.getOutputStream());
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
