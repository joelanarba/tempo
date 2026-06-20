package com.tempoapi.modules.spaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record UpdateSpaceRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String location,
        @Size(max = 2000) String description,
        @NotNull LocalTime openingTime,
        @NotNull LocalTime closingTime) {
}
