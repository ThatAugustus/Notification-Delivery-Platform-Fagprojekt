package com.app.demo.service;

import com.app.demo.model.Tenant;

public class TenantLifecycleEvent {
    public enum Action {
        CREATED, UPDATED, SOFT_DELETED, RESTORED, DELETED
    }

    private final Tenant tenant;
    private final Action action;

    public TenantLifecycleEvent(Tenant tenant, Action action) {
        this.tenant = tenant;
        this.action = action;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public Action getAction() {
        return action;
    }
}
