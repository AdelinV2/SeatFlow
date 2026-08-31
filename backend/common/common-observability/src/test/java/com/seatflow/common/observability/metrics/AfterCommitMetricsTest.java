package com.seatflow.common.observability.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AfterCommitMetricsTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void shouldRunImmediatelyWithoutTransaction() {
        AtomicInteger count = new AtomicInteger();

        AfterCommitMetrics.afterCommit(count::incrementAndGet);

        assertThat(count).hasValue(1);
    }

    @Test
    void shouldRunOnlyAfterCommit() {
        AtomicInteger count = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        AfterCommitMetrics.afterCommit(count::incrementAndGet);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();

        assertThat(count).hasValue(0);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(count).hasValue(1);
    }

    @Test
    void shouldNotRunAfterRollback() {
        AtomicInteger count = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        AfterCommitMetrics.afterCommit(count::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(count).hasValue(0);
    }

    @Test
    void shouldSwallowMetricFailureAfterCommit() {
        assertThatCode(() -> AfterCommitMetrics.afterCommit(() -> {
            throw new IllegalStateException("registry unavailable");
        })).doesNotThrowAnyException();
    }
}
