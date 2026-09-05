package com.example.moneyload.application;

import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import java.util.Objects;

/** Duplicate input is distinct from a completed accepted or declined evaluation. */
public sealed interface LoadOutcome {
    record Completed(StoredLoadResult result) implements LoadOutcome {
        public Completed {
            Objects.requireNonNull(result, "result");
        }

        public boolean accepted() {
            return result.decision().accepted();
        }
    }

    /** Retains the original decision for future recovery of ambiguous outcomes. */
    record Duplicate(StoredLoadResult originalResult) implements LoadOutcome {
        public Duplicate {
            Objects.requireNonNull(originalResult, "originalResult");
        }
    }
}
