package com.seatflow.common.observability.metrics;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Defers business outcome metrics until the surrounding database transaction commits.
 */
public final class AfterCommitMetrics {

    private AfterCommitMetrics() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static void afterCommit(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        safelyRun(action);
                    }
                });
            } catch (RuntimeException ignored) {
                // A synchronization failure must not affect the active transaction.
            }
            return;
        }
        safelyRun(action);
    }

    private static void safelyRun(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Metrics are best effort and must never affect business processing.
        }
    }
}
