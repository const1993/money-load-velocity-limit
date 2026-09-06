package com.example.moneyload.adapter.inbound.file;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

public interface LoadFileProcessor {
    default Counts process(BufferedReader input, BufferedWriter output) throws IOException {
        return process(input, output, false);
    }

    /** The caller owns the streams; implementations preserve input order in their output. */
    Counts process(BufferedReader input, BufferedWriter output, boolean includeDuplicates) throws IOException;

    record Counts(long processed, long accepted, long declined, long duplicates) {
        Counts including(com.example.moneyload.application.LoadOutcome outcome) {
            return switch (outcome) {
                case com.example.moneyload.application.LoadOutcome.Completed completed ->
                        new Counts(processed + 1, accepted + (completed.accepted() ? 1 : 0),
                                declined + (completed.accepted() ? 0 : 1), duplicates);
                case com.example.moneyload.application.LoadOutcome.Duplicate ignored ->
                        new Counts(processed + 1, accepted, declined, duplicates + 1);
            };
        }
    }
}
