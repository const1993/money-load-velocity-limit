package com.example.moneyload.adapter.inbound.file;

public class FileLoadProcessingException extends IllegalStateException {
    public FileLoadProcessingException(long lineNumber, RuntimeException cause) {
        super("Load processing failed at line " + lineNumber, cause);
    }

    public RuntimeException serviceFailure() {
        return (RuntimeException) getCause();
    }
}
