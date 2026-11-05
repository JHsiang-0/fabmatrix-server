package com.example.farm.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CloudPairingRequest(@Min(0) long revision, @NotNull UUID pairingId) { }
