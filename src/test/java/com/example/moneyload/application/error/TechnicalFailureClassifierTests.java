package com.example.moneyload.application.error;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.*;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.*;

import static org.assertj.core.api.Assertions.*;

class TechnicalFailureClassifierTests {
    private final TechnicalFailureClassifier classifier = new TechnicalFailureClassifier();

    @ParameterizedTest
    @MethodSource("transientFailures")
    void recognizesOnlyExplicitTransientFailures(RuntimeException failure) {
        assertThat(classifier.classify(failure).transientFailure()).isTrue();
    }

    static Stream<RuntimeException> transientFailures() {
        return Stream.of(new TransientDataAccessResourceException("Transient connection"),
                new CannotAcquireLockException("Lock conflict"), new QueryTimeoutException("Timeout"),
                new RecoverableDataAccessException("Recoverable connection"),
                new TransactionTimedOutException("Transaction timed out"),
                new CannotCreateTransactionException("Begin failed", new SQLTransientConnectionException()),
                new CannotGetJdbcConnectionException("Connection failed", new SQLTransientConnectionException()),
                new TransactionSystemException("Commit uncertain", new SQLRecoverableException()),
                new TransactionSystemException("Serialization failure", new SQLTransactionRollbackException()));
    }

    @ParameterizedTest
    @MethodSource("nonTransient")
    void rejectsUnknownProgrammingValidationAndPersistentFailures(RuntimeException failure) {
        assertThat(classifier.classify(failure)).isEqualTo(TechnicalFailureClassifier.Category.NON_TRANSIENT);
    }

    static Stream<RuntimeException> nonTransient() {
        return Stream.of(new IllegalArgumentException("Invalid input"), new IllegalStateException("Missing bucket"),
                new NullPointerException("Mapping bug"), new DataIntegrityViolationException("Constraint"),
                new DuplicateKeyException("Duplicate without recoverable result"),
                new BadSqlGrammarException("Query", "invalid", new SQLSyntaxErrorException()),
                new DataAccessResourceFailureException("Unclassified connection failure"),
                new UnexpectedRollbackException("Rollback only"), new TransactionSystemException("Unknown commit failure"),
                new CannotGetJdbcConnectionException("Unknown connection failure", new SQLException()),
                new IllegalStateException("Programming error wrapping transient cause", new SQLTransientConnectionException()));
    }

    @Test
    void failedRollbackDoesNotHideOriginalApplicationFailure() {
        var failure = new TransactionSystemException("Rollback failure", new SQLTransientConnectionException());
        failure.initApplicationException(new IllegalStateException("Original failure"));
        assertThat(classifier.classify(failure).transientFailure()).isFalse();
    }
}
