package com.example.farm.protocol;

import com.example.farm.protocol.observation.PrinterObservation.HeaterRole;
import java.math.BigDecimal;
import java.util.Objects;

/** A freshly probed, unambiguous physical route; never accepted from a user request. */
public record HeaterControlChannel(String id, HeaterRole role, BigDecimal minC, BigDecimal maxC,
                                   Integer toolNumber, Integer bedSlot) {
    public boolean sameRoute(HeaterControlChannel other) {
        return other != null && Objects.equals(id, other.id) && role == other.role
                && Objects.equals(toolNumber, other.toolNumber) && Objects.equals(bedSlot, other.bedSlot);
    }
}
