package com.app.demo.repository;

import java.util.UUID;

public interface PendingOutboxTenantView {
    UUID getTenantId();

    String getTenantName();
}