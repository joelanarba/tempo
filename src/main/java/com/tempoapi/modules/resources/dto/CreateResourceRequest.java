package com.tempoapi.modules.resources.dto;

import com.tempoapi.entity.ResourceType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateResourceRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull ResourceType type,
        @Min(1) Integer capacity) {
}
