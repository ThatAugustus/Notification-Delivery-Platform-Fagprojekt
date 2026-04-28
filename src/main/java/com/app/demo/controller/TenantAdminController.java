
package com.app.demo.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.demo.dto.CreateTenantRequest;
import com.app.demo.dto.TenantResponse;
import com.app.demo.dto.UpdateTenantRequest;
import com.app.demo.model.Tenant;
import com.app.demo.service.TenantManagementService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/tenants")
public class TenantAdminController {

    private final TenantManagementService service;

    public TenantAdminController(TenantManagementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant created = service.create(request);
        return ResponseEntity.status(201).body(TenantResponse.from(created));
    }

    @GetMapping
    public ResponseEntity<List<TenantResponse>> list() {
        List<TenantResponse> tenants = service.listActive().stream()
                .map(TenantResponse::from)
                .toList();
        return ResponseEntity.ok(tenants);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(TenantResponse.from(service.getActive(id)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TenantResponse> update(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateTenantRequest request) {
        Tenant updated = service.update(id, request);
        return ResponseEntity.ok(TenantResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build(); // 204
    }

    // Restore a soft-deleted tenant. Idempotent: no error if already active.
    @PostMapping("/{id}/restore")
    public ResponseEntity<TenantResponse> restore(@PathVariable UUID id) {
        Tenant restored = service.restore(id);
        return ResponseEntity.ok(TenantResponse.from(restored));
    }
}
