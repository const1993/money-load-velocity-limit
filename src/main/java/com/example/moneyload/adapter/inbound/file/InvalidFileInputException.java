package com.example.moneyload.adapter.inbound.file;

public class InvalidFileInputException extends IllegalArgumentException {
    private final long lineNumber;

    public InvalidFileInputException(long lineNumber) {
        super("Invalid file input at line " + lineNumber);
        this.lineNumber = lineNumber;
    }
    public long lineNumber() {
        return lineNumber;
    }
}
