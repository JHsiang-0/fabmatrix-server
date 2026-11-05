package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.observation.PrinterObservation;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Persists the latest validated firmware-neutral device state in the Local SQLite database. */
public interface CanonicalPrinterStateService {
    /** Distinguishes a single invalid persisted model from a storage or service failure. */
    final class InvalidStoredModelException extends IllegalStateException {
        public InvalidStoredModelException(Throwable cause) {
            super("Stored canonical printer model is invalid", cause);
        }
    }

    boolean persist(Printer printer, PrinterObservation observation);

    /**
     * Returns the latest validated measurement snapshot with current local connection health overlaid,
     * or null before its first observation or after its printer asset has been removed.
     */
    CanonicalPrinterModel findLatest(long printerId);

    /** Reads a bounded model for cloud publication; oversized snapshots are omitted. */
    CanonicalPrinterModel findLatestForCloud(long printerId);

    /** 批量查询列表所需的模型，生产实现以有界 SQL 批次读取。 */
    default Map<Long, CanonicalPrinterModel> findLatestBatch(List<Printer> printers) {
        Map<Long, CanonicalPrinterModel> result = new HashMap<>();
        for (Printer printer : printers) {
            if (printer.getId() == null) { continue; }
            var model = findLatest(printer.getId());
            if (model != null) { result.put(printer.getId(), model); }
        }
        return result;
    }
}
