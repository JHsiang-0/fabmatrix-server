package com.example.farm.service;

import com.example.farm.controller.FarmStatusMessage;
import com.example.farm.entity.PrintJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketEventPublisherTest {

    private final RecordingPublisher publisher = new RecordingPublisher();

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesJobStatusAfterTransactionCommit() {
        PrintJob job = job();
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishJobStatus(job);
        assertThat(publisher.broadcastCount).isZero();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(publisher.broadcastCount).isEqualTo(1);
    }

    @Test
    void doesNotPublishJobStatusAfterTransactionRollback() {
        PrintJob job = job();
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishJobStatus(job);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(publisher.broadcastCount).isZero();
    }

    @Test
    void publishesImmediatelyWhenNoTransactionSynchronizationIsActive() {
        publisher.publishJobStatus(job());

        assertThat(publisher.broadcastCount).isEqualTo(1);
    }

    private PrintJob job() {
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setPrinterId(403L);
        job.setStatus("PRINTING");
        return job;
    }

    private static class RecordingPublisher extends WebSocketEventPublisher {
        private int broadcastCount;

        @Override
        protected void broadcastJobStatus(FarmStatusMessage message) {
            broadcastCount++;
        }
    }
}
