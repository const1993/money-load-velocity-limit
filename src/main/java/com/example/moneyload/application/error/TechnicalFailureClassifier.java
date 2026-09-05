package com.example.moneyload.application.error;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

/** Reuses Spring/JDBC's technical exception taxonomy; unknown failures fail closed. */
@Component
public class TechnicalFailureClassifier {
    public enum Category {
        TRANSIENT_DATABASE, TRANSIENT_TRANSACTION, NON_TRANSIENT;

        public boolean transientFailure() {
            return this != NON_TRANSIENT;
        }
    }

    public Category classify(RuntimeException failure) {
        Throwable current = failure;
        // Only unwrap recognized infrastructure wrappers, never arbitrary programming errors.
        // A depth limit also makes cyclic or pathological cause chains non-transient.
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof TransactionSystemException transactionFailure
                    && transactionFailure.getApplicationException() != null) {
                // Rollback itself failed: do not hide the original application failure.
                return Category.NON_TRANSIENT;
            }
            if (current instanceof TransactionTimedOutException || current instanceof SQLTransactionRollbackException) {
                return Category.TRANSIENT_TRANSACTION;
            }
            if (current instanceof TransientDataAccessException || current instanceof RecoverableDataAccessException
                    || current instanceof SQLTransientException || current instanceof SQLRecoverableException) {
                return Category.TRANSIENT_DATABASE;
            }
            if (current instanceof TransactionSystemException || current instanceof CannotCreateTransactionException
                    || current instanceof CannotGetJdbcConnectionException) {
                current = current.getCause();
            } else {
                return Category.NON_TRANSIENT;
            }
        }
        return Category.NON_TRANSIENT;
    }
}
