package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.observation.PrinterObservation;

/** Persists the latest validated firmware-neutral device state in the Local SQLite database. */
public interface CanonicalPrinterStateService {
    boolean persist(Printer printer, PrinterObservation observation);

    /**
     * Returns the latest validated measurement snapshot with current local connection health overlaid,
     * or null before its first observation or after its printer asset has been removed.
     */
    CanonicalPrinterModel findLatest(long printerId);
}
