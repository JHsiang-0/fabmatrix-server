package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.protocol.observation.PrinterObservation;

/** Persists the latest validated firmware-neutral device state in the Local SQLite database. */
public interface CanonicalPrinterStateService {
    boolean persist(Printer printer, PrinterObservation observation);
}
