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

    record Counts(long processed, long accepted, long declined, long duplicates) { }
}
