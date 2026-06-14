
-- V6: Add soft delete support for tenants.
-- Business-critical notifications require preserving history even after
-- a tenant leaves the platform (audit trails, regulatory compliance, reactivation).

ALTER TABLE tenants
    ADD COLUMN deleted_at TIMESTAMPTZ NULL;

-- Partial index: only index active tenants. Soft-deleted rows don't hit the index.
-- Makes "list active tenants" and "lookup by id where not deleted" queries fast.
CREATE INDEX idx_tenants_active
    ON tenants (id)
    WHERE deleted_at IS NULL;
