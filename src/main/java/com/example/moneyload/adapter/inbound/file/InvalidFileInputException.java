package com.example.moneyload.adapter.inbound.file;

public class InvalidFileInputException extends IllegalArgumentException {
    public InvalidFileInputException(long lineNumber) {
        super("Invalid file input at line " + lineNumber);
    }
}
