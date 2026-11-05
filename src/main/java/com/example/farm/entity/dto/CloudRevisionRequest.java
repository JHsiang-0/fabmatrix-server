package com.example.farm.entity.dto;

import jakarta.validation.constraints.Min;

public record CloudRevisionRequest(@Min(0) long revision) {
}
