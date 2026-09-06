package com.example.moneyload.adapter.inbound.file;

public class FileLoadProcessingException extends IllegalStateException {
    private final long lineNumber;

    public FileLoadProcessingException(long lineNumber, RuntimeException cause) {
        super("Load processing failed at line " + lineNumber, cause);
        this.lineNumber = lineNumber;
    }

    public RuntimeException serviceFailure() {
        return (RuntimeException) getCause();
    }
    public long lineNumber() {
        return lineNumber;
    }
}
