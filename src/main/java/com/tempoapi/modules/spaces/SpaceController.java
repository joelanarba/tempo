package com.tempoapi.modules.spaces;

import com.tempoapi.common.ApiResponse;
import com.tempoapi.common.PageResponse;
import com.tempoapi.modules.spaces.dto.CreateSpaceRequest;
import com.tempoapi.modules.spaces.dto.SpaceResponse;
import com.tempoapi.modules.spaces.dto.UpdateSpaceRequest;
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
@RequestMapping("/api/v1/spaces")
public class SpaceController {

    private final SpaceService spaceService;

    public SpaceController(SpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SpaceResponse>> create(@Valid @RequestBody CreateSpaceRequest request) {
        SpaceResponse response = spaceService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Space created", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<SpaceResponse>>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(spaceService.list(pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SpaceResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(spaceService.getById(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SpaceResponse>> update(@PathVariable UUID id,
                                                             @Valid @RequestBody UpdateSpaceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Space updated", spaceService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        spaceService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok("Space deactivated", null));
    }
}
