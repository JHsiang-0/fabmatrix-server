package com.example.farm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.mapper.GCodeMetadataStore;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.PrinterExecutionMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.file.GCodeFileInspection;
import com.example.farm.protocol.file.GCodeFileInspector;
import com.example.farm.protocol.file.PrinterFileMetadataReader;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.model.GCodeFileMetadata;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.io.IOException;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** File I/O is isolated from status polling. Persisted results remain tied to a verified execution. */
@Service
@Profile("local")
public class GCodeMetadataService {
    private static final Logger LOG = LoggerFactory.getLogger(GCodeMetadataService.class);
    private static final long RETRY_MILLIS = 60000;
    private final GCodeMetadataStore store;
    private final GCodeFileInspector inspector;
    private final PrinterFileMetadataReader reader;
    private final FileStorage files;
    private final PrinterMapper printers;
    private final PrinterExecutionMapper executions;
    private final PrintJobMapper jobs;
    private final PrintFileMapper printFiles;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("farm-gcode-reader").factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> retries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, GCodeMetadataStore.Entry> cached = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public GCodeMetadataService(GCodeMetadataStore store, GCodeFileInspector inspector, PrinterFileMetadataReader reader,
                               FileStorage files, PrinterMapper printers, PrinterExecutionMapper executions,
                               PrintJobMapper jobs, PrintFileMapper printFiles) {
        this.store = store;
        this.inspector = inspector;
        this.reader = reader;
        this.files = files;
        this.printers = printers;
        this.executions = executions;
        this.jobs = jobs;
        this.printFiles = printFiles;
    }

    public void uploaded(PrintFile file) {
        Long id = file.getId();
        String safeName = file.getSafeName();
        String originalName = file.getOriginalName();
        Long size = file.getFileSize();
        Runnable inspect = () -> enqueue("upload:" + id, () -> inspectUpload(id, safeName, originalName, size));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { inspect.run(); }
            });
        } else {
            inspect.run();
        }
    }

    public CanonicalPrinterModel enrich(Printer printer, PrinterExecution active, String filename,
                                         CanonicalPrinterModel model) {
        if (active == null || filename == null || filename.isBlank() || active.getExecutionUid() == null
                || model.observation().currentExecution() == null) return model;
        String key = "execution:" + active.getExecutionUid();
        try {
            var request = new Request(printer.getId(), printer.getDeviceUid(), active.getExecutionUid(), filename,
                    model.observation().currentExecution().file() == null ? null : model.observation().currentExecution().file().sizeBytes(),
                    missing(model.observation().currentExecution().layers().current()));
            var entry = cached.get(printer.getId());
            if (entry != null && Objects.equals(entry.printerId(), printer.getId())
                    && Objects.equals(entry.executionUid(), active.getExecutionUid())
                    && Objects.equals(entry.deviceUid(), printer.getDeviceUid())
                    && Objects.equals(entry.deviceFilename(), filename)
                    && sameSize(model.observation().currentExecution(), entry.inspection())) {
                if (entry.readerRevision() < GCodeMetadataStore.CURRENT_READER_REVISION) {
                    enqueue(key, () -> inspectExecution(request));
                }
                return GCodeExecutionEnrichment.apply(model, entry.inspection());
            }
            enqueue(key, () -> inspectExecution(request));
        } catch (Exception failure) {
            // A file cache failure must not turn a successful device poll into an unavailable state.
            warn(key, printer.getId());
        }
        return model;
    }

    private record Request(Long printerId, String deviceUid, String executionUid, String filename,
                           Long expectedSize, boolean layerIndex) {
        @Override public String toString() { return "FileInspectionRequest[redacted]"; }
    }

    private void inspectUpload(Long id, String safeName, String originalName, Long size) throws Exception {
        String key = "upload:" + id;
        var stored = store.find(key);
        if (stored != null && stored.readerRevision() >= GCodeMetadataStore.CURRENT_READER_REVISION
                || printFiles.selectById(id) == null) return;
        try (var input = files.getFileStream(safeName).getInputStream()) {
            var inspection = inspector.inspect(input, originalName, size == null ? -1 : size, "SERVER_UPLOAD");
            var current = printFiles.selectById(id);
            if (current != null && Objects.equals(current.getSafeName(), safeName)) {
                promoteLocalThumbnail(current, inspection);
                if (printFiles.selectById(id) != null) save(new GCodeMetadataStore.Entry(key, null, null, null, null, inspection));
            }
        }
    }

    private void promoteLocalThumbnail(PrintFile file, GCodeFileInspection inspection) throws Exception {
        var image = inspection.thumbnails().stream().max(Comparator.comparingLong(value ->
                (long) value.descriptor().width() * value.descriptor().height())).orElse(null);
        if (image == null || closed) return;
        String key = "thumbnails/print-" + file.getId() + "-" + UUID.randomUUID() + ".png";
        String url = files.uploadBytes(key, image.png(), "image/png");
        if (url == null || url.isBlank()) throw new IOException("FILE_PREVIEW_STORE_FAILED");
        try {
            var update = new UpdateWrapper<PrintFile>().eq("id", file.getId()).eq("safe_name", file.getSafeName())
                    .eq(file.getThumbnailUrl() != null, "thumbnail_url", file.getThumbnailUrl())
                    .isNull(file.getThumbnailUrl() == null, "thumbnail_url").set("thumbnail_url", url);
            if (closed || printFiles.update(null, update) != 1) {
                files.deleteFile(key);
                return;
            }
        } catch (Exception failure) {
            files.deleteFile(key);
            throw failure;
        }
        if (file.getThumbnailUrl() != null && !file.getThumbnailUrl().equals(url)) {
            try { files.deleteFileByObjectUrl(file.getThumbnailUrl()); }
            catch (RuntimeException cleanup) { LOG.debug("Previous file preview cleanup deferred: fileId={}", file.getId()); }
        }
    }

    private void inspectExecution(Request request) throws Exception {
        var printer = printers.selectById(request.printerId());
        if (printer == null || !request.deviceUid().equals(printer.getDeviceUid())) return;
        var stored = store.find("execution:" + request.executionUid());
        if (stored != null && Objects.equals(stored.deviceUid(), request.deviceUid())
                && Objects.equals(stored.printerId(), request.printerId())
                && Objects.equals(stored.deviceFilename(), request.filename())
                && (request.expectedSize() == null || stored.inspection().metadata().sizeBytes() == null
                    || request.expectedSize().equals(stored.inspection().metadata().sizeBytes()))) {
            remember(stored);
            if (stored.readerRevision() >= GCodeMetadataStore.CURRENT_READER_REVISION) return;
        }
        var execution = execution(request);
        if (execution == null || !sameName(execution.getObservedFilename(), request.filename())) return;
        var endpoint = new PrinterEndpoint(printer.getId(), printer.getIpAddress(), printer.getApiKey(),
                PrinterProtocolType.normalize(printer.getFirmwareType()));
        var local = fromLocalJob(execution, request.filename());
        boolean localFile = local != null;
        GCodeFileInspection inspection = localFile ? local.inspection() : null;
        int readerRevision = localFile ? local.readerRevision() : GCodeMetadataStore.CURRENT_READER_REVISION;
        if (!localFile) {
            if (!"ACTIVE".equals(execution.getState())) return;
            inspection = reader.read(endpoint, request.filename(), request.layerIndex());
        }
        if (Thread.currentThread().isInterrupted()) return;
        printer = printers.selectById(request.printerId());
        execution = execution(request);
        if (printer == null || !request.deviceUid().equals(printer.getDeviceUid()) || execution == null
                || !localFile && !"ACTIVE".equals(execution.getState())
                || !Objects.equals(endpoint.ipAddress(), printer.getIpAddress())
                || !Objects.equals(endpoint.apiKey(), printer.getApiKey())
                || endpoint.protocolType() != PrinterProtocolType.normalize(printer.getFirmwareType())
                || !sameName(execution.getObservedFilename(), request.filename())
                || execution.getFileSize() != null && inspection.metadata().sizeBytes() != null
                && execution.getFileSize().compareTo(BigDecimal.valueOf(inspection.metadata().sizeBytes())) != 0) return;
        save(new GCodeMetadataStore.Entry("execution:" + request.executionUid(), request.printerId(),
                request.executionUid(), request.deviceUid(), request.filename(), inspection, readerRevision));
    }

    private PrinterExecution execution(Request request) {
        return executions.selectOne(Wrappers.<PrinterExecution>lambdaQuery()
                .eq(PrinterExecution::getPrinterId, request.printerId())
                .eq(PrinterExecution::getExecutionUid, request.executionUid()));
    }

    private GCodeMetadataStore.Entry fromLocalJob(PrinterExecution execution, String filename) throws Exception {
        if (!"FARM".equals(execution.getSource()) || execution.getFarmJobId() == null) return null;
        var job = jobs.selectById(execution.getFarmJobId());
        if (job == null || !Objects.equals(job.getPrinterId(), execution.getPrinterId()) || job.getFileId() == null) return null;
        var file = printFiles.selectById(job.getFileId());
        if (file == null || !sameName(filename, file.getOriginalName()) && !sameName(filename, file.getSafeName())) return null;
        var upload = store.find("upload:" + file.getId());
        if (upload == null || upload.readerRevision() < GCodeMetadataStore.CURRENT_READER_REVISION) {
            try { inspectUpload(file.getId(), file.getSafeName(), file.getOriginalName(), file.getFileSize()); }
            catch (Exception failure) {
                if (upload == null) throw failure;
                warn("execution:" + execution.getExecutionUid(), execution.getPrinterId());
            }
            upload = store.find("upload:" + file.getId());
        }
        if (upload == null) return null;
        var data = upload.inspection();
        // Runtime files may use the generated safe name. Public names follow the actual execution.
        var metadata = data.metadata();
        var renamed = new GCodeFileMetadata(metadata.schemaVersion(), metadata.fileId(),
                publicName(filename), metadata.sizeBytes(), metadata.sha256(), metadata.source(), metadata.parsedAt(),
                metadata.slicerName(), metadata.slicerVersion(), metadata.estimatedPrintSeconds(), metadata.totalLayers(),
                metadata.layerHeightMm(), metadata.firstLayerHeightMm(), metadata.nozzleDiameterMm(), metadata.objectHeightMm(),
                metadata.nozzleTemperatureC(), metadata.firstLayerNozzleTemperatureC(), metadata.bedTemperatureC(),
                metadata.firstLayerBedTemperatureC(), metadata.filaments(), metadata.thumbnails());
        return new GCodeMetadataStore.Entry(upload.cacheKey(), upload.printerId(), upload.executionUid(), upload.deviceUid(),
                upload.deviceFilename(), new GCodeFileInspection(renamed, store.images(upload), data.layers()), upload.readerRevision());
    }

    private void save(GCodeMetadataStore.Entry entry) {
        if (closed) return;
        files.ensureCapacity(entry.inspection().thumbnails().stream().mapToLong(image -> image.png().length).sum() + 65536);
        store.save(entry);
        remember(new GCodeMetadataStore.Entry(entry.cacheKey(), entry.printerId(), entry.executionUid(), entry.deviceUid(),
                entry.deviceFilename(), new GCodeFileInspection(entry.inspection().metadata(), List.of(), entry.inspection().layers()),
                entry.readerRevision()));
    }

    private void remember(GCodeMetadataStore.Entry entry) {
        if (entry.printerId() == null) return;
        Long printerId = entry.printerId();
        if (cached.size() >= 256 && !cached.containsKey(entry.printerId())) {
            var keys = cached.keySet().iterator();
            if (keys.hasNext()) cached.remove(keys.next());
        }
        long marks = cached.values().stream().filter(item -> !printerId.equals(item.printerId()))
                .mapToLong(item -> item.inspection().layers().size()).sum();
        if (marks + entry.inspection().layers().size() > 2_000_000) {
            entry = new GCodeMetadataStore.Entry(entry.cacheKey(), entry.printerId(), entry.executionUid(), entry.deviceUid(),
                    entry.deviceFilename(), new GCodeFileInspection(entry.inspection().metadata(), List.of(), List.of()), entry.readerRevision());
        }
        cached.put(entry.printerId(), entry);
    }

    private void enqueue(String key, Work work) {
        if (System.currentTimeMillis() < retries.getOrDefault(key, 0L) || !pending.add(key)) return;
        try {
            worker.execute(() -> {
                try { work.run(); }
                catch (Exception failure) { warn(key, null); }
                finally { pending.remove(key); }
            });
        } catch (RejectedExecutionException full) { pending.remove(key); }
    }

    private void warn(String key, Long printerId) {
        long now = System.currentTimeMillis();
        if (retries.size() >= 512) retries.clear();
        Long previous = retries.put(key, now + RETRY_MILLIS);
        if (previous == null || previous <= now) LOG.warn("G-code metadata will retry: cacheKey={}, printerId={}", key, printerId);
    }

    private static boolean sameSize(CanonicalPrinterModel.Execution execution, GCodeFileInspection inspection) {
        return execution.file() == null || execution.file().sizeBytes() == null || inspection.metadata().sizeBytes() == null
                || execution.file().sizeBytes().equals(inspection.metadata().sizeBytes());
    }

    private static boolean missing(CanonicalPrinterModel.Measurement<?> value) {
        return value == null || value.quality() == CanonicalPrinterModel.Quality.UNAVAILABLE
                || value.quality() == CanonicalPrinterModel.Quality.UNSUPPORTED;
    }

    private static boolean sameName(String first, String second) {
        return first != null && second != null && basename(first).equals(basename(second));
    }

    private static String publicName(String filename) {
        String name = basename(filename).replaceAll("[/\\\\:\\p{Cntrl}]", "_");
        return name.length() > 128 ? name.substring(0, 128) : name;
    }

    private static String basename(String filename) {
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return filename.substring(separator + 1);
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }

    @PreDestroy public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
