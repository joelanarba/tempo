package com.tempoapi.modules.resources;

import com.tempoapi.common.ApiResponse;
import com.tempoapi.common.PageResponse;
import com.tempoapi.modules.resources.dto.CreateResourceRequest;
import com.tempoapi.modules.resources.dto.ResourceResponse;
import com.tempoapi.modules.resources.dto.UpdateResourceRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @PostMapping("/spaces/{spaceId}/resources")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ResourceResponse>> create(@PathVariable UUID spaceId,
                                                                @Valid @RequestBody CreateResourceRequest request) {
        ResourceResponse response = resourceService.create(spaceId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Resource created", response));
    }

    @GetMapping("/spaces/{spaceId}/resources")
    public ResponseEntity<ApiResponse<PageResponse<ResourceResponse>>> listForSpace(
            @PathVariable UUID spaceId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(resourceService.listForSpace(spaceId, pageable)));
    }

    @GetMapping("/resources/{id}")
    public ResponseEntity<ApiResponse<ResourceResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(resourceService.getById(id)));
    }

    @PutMapping("/resources/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ResourceResponse>> update(@PathVariable UUID id,
                                                                @Valid @RequestBody UpdateResourceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Resource updated", resourceService.update(id, request)));
    }

    @DeleteMapping("/resources/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        resourceService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok("Resource deactivated", null));
    }
}
