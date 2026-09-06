package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.application.error.TechnicalFailureClassifier;
import com.example.moneyload.adapter.inbound.file.InvalidFileInputException;
import com.example.moneyload.adapter.inbound.file.FileLoadProcessingException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class LoadExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(LoadExceptionHandler.class);
    private final TechnicalFailureClassifier classifier;

    public LoadExceptionHandler(TechnicalFailureClassifier classifier) {
        this.classifier = classifier;
    }

    @ExceptionHandler(InvalidLoadRequestException.class)
    ResponseEntity<Object> invalid(InvalidLoadRequestException failure, WebRequest request) {
        return response(HttpStatus.BAD_REQUEST, new HttpHeaders(), request);
    }

    @ExceptionHandler(InvalidFileInputException.class)
    ResponseEntity<Object> invalidFile(InvalidFileInputException failure, WebRequest request) {
        log.atWarn().addKeyValue("event", "file_input_invalid").addKeyValue("line_number", failure.lineNumber())
                .log("File input validation failed");
        var id = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        return new ResponseEntity<>(new ApiError("INVALID_REQUEST", failure.getMessage(), id), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(FileLoadProcessingException.class)
    ResponseEntity<Object> failedFile(FileLoadProcessingException failure, WebRequest request) {
        boolean transientFailure = classifier.classify(failure.serviceFailure()).transientFailure();
        logFailure(transientFailure ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR", failure);
        return response(transientFailure ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
                new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure, WebRequest request) {
        boolean transientFailure = failure instanceof RuntimeException runtime
                && classifier.classify(runtime).transientFailure();
        logFailure(transientFailure ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR", failure);
        return response(transientFailure ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
                new HttpHeaders(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            logFailure("INTERNAL_ERROR", failure);
        }
        return response(status, headers, request);
    }

    private ResponseEntity<Object> response(HttpStatusCode status, HttpHeaders headers, WebRequest request) {
        String code = status.value() == 503 ? "SERVICE_UNAVAILABLE"
                : status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR";
        String message = switch (code) {
            case "INVALID_REQUEST" -> "Request does not match the required format.";
            case "SERVICE_UNAVAILABLE" -> "Load processing is temporarily unavailable.";
            default -> "Load processing failed.";
        };
        var id = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        return new ResponseEntity<>(new ApiError(code, message, id), headers, status);
    }

    private void logFailure(String category, Exception failure) {
        var event = log.atError().addKeyValue("event", "load_request_failed")
                .addKeyValue("failure_category", category).addKeyValue("exception_type", failure.getClass().getName());
        if (failure instanceof FileLoadProcessingException fileFailure) {
            event.addKeyValue("line_number", fileFailure.lineNumber());
        }
        // Stack locations and cause types aid diagnosis without logging SQL/payload-bearing messages.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            event.addKeyValue("cause_" + depth + "_type", cause.getClass().getName())
                    .addKeyValue("cause_" + depth + "_frames", Arrays.stream(cause.getStackTrace()).limit(16).toList());
        }
        event.log("Load request failed");
    }
}
