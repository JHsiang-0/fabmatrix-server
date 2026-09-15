package com.example.farm.service;

import com.example.farm.controller.FarmStatusMessage;
import com.example.farm.entity.PrintJob;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;

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

    @Test
    void snapshotsEachJobStatusAtPublishTimeWithinTheSameTransaction() {
        PrintJob job = job();
        TransactionSynchronizationManager.initSynchronization();

        job.setStatus("UPLOADING");
        publisher.publishJobStatus(job);
        job.setStatus("PRINTING");
        publisher.publishJobStatus(job);

        assertThat(publisher.messages).isEmpty();
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(publisher.messages).hasSize(2);
        assertThat(publisher.messages)
                .extracting(message -> String.valueOf(
                        ((java.util.Map<?, ?>) message.data()).get("status")))
                .containsExactly("UPLOADING", "PRINTING");
        assertThat(publisher.messages)
                .extracting(FarmStatusMessage::eventId)
                .doesNotHaveDuplicates();
    }

    @Test
    void publishesUnboundJobAndClearsBindingForTerminalStatus() {
        PrintJob job = job();
        job.setPrinterId(null);
        job.setStatus("COMPLETED");

        publisher.publishJobStatus(job);

        assertThat(publisher.broadcastCount).isEqualTo(1);
        assertThat(publisher.lastMessage.printerId()).isNull();
        assertThat(publisher.lastMessage.data()).isInstanceOf(java.util.Map.class);
        var data = (java.util.Map<?, ?>) publisher.lastMessage.data();
        assertThat(data.get("jobId")).isEqualTo("1001");
        assertThat(data.get("currentJobId")).isNull();
    }

    @Test
    void onlyAdvertisesActiveBindingWhenPrinterActuallyPointsToTheJob() {
        PrinterService printerService = org.mockito.Mockito.mock(PrinterService.class);
        com.example.farm.entity.Printer printer = new com.example.farm.entity.Printer();
        printer.setId(403L);
        printer.setCurrentJobId(1001L);
        org.mockito.Mockito.when(printerService.getById(403L)).thenReturn(printer);
        publisher.setPrinterService(printerService);

        publisher.publishJobStatus(job());

        var data = (java.util.Map<?, ?>) publisher.lastMessage.data();
        assertThat(data.get("currentJobId")).isEqualTo("1001");

        printer.setCurrentJobId(1002L);
        publisher.publishJobStatus(job());
        var unboundData = (java.util.Map<?, ?>) publisher.lastMessage.data();
        assertThat(unboundData.get("currentJobId")).isNull();
    }

    @Test
    void normalizesDeprecatedPreparingJobStatusToUploading() {
        PrintJob job = job();
        job.setStatus("PREPARING");

        publisher.publishJobStatus(job);

        var data = (java.util.Map<?, ?>) publisher.lastMessage.data();
        assertThat(data.get("status")).isEqualTo("UPLOADING");
    }

    @Test
    void queuedStatusAlwaysClearsCurrentBinding() {
        PrintJob job = job();
        job.setStatus("QUEUED");

        publisher.publishJobStatus(job);

        var data = (java.util.Map<?, ?>) publisher.lastMessage.data();
        assertThat(data.get("currentJobId")).isNull();
    }

    @Test
    void publishesExternalTaskObservationWithoutClaimingFarmOwnership() {
        PrinterDeviceStatus status = new PrinterDeviceStatus(
                PrinterStatus.PRINTING, "processing", null, "external.gcode", BigDecimal.TEN,
                null, null, null, null, null, null, null);

        publisher.publishPrinterStatus(403L, status, "EXTERNAL", 1001L, "READY",
                "FILENAME_MISMATCH");

        var data = (java.util.Map<?, ?>) publisher.lastMessage.data();
        assertThat(data.get("taskSource")).isEqualTo("EXTERNAL");
        assertThat(data.get("observedFilename")).isEqualTo("external.gcode");
        assertThat(data.get("farmJobId")).isEqualTo("1001");
        assertThat(data.get("currentJobId")).isEqualTo("1001");
        assertThat(data.get("currentJobStatus")).isEqualTo("READY");
        assertThat(data.get("taskMatchReason")).isEqualTo("FILENAME_MISMATCH");
    }

    @Test
    void publishesLayoutUpdateOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishLayoutUpdated(2L);
        assertThat(publisher.layoutBroadcastCount).isZero();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(publisher.layoutBroadcastCount).isEqualTo(1);
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
        private int layoutBroadcastCount;
        private FarmStatusMessage lastMessage;
        private final List<FarmStatusMessage> messages = new ArrayList<>();

        @Override
        protected void broadcastJobStatus(FarmStatusMessage message) {
            broadcastCount++;
            lastMessage = message;
            messages.add(message);
        }

        @Override
        protected void broadcastPrinterStatus(FarmStatusMessage message) {
            broadcastCount++;
            lastMessage = message;
            messages.add(message);
        }

        @Override
        protected void broadcastLayoutUpdated(FarmStatusMessage message) {
            layoutBroadcastCount++;
        }
    }
}
