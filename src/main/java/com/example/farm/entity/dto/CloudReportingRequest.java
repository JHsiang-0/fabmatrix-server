package com.example.farm.entity.dto;

import jakarta.validation.constraints.Min;

public record CloudReportingRequest(@Min(0) long revision, boolean enabled) {
}
