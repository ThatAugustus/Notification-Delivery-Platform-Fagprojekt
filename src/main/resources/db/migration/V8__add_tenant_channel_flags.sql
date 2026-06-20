-- V8: Add per-tenant channel enablement flags
ALTER TABLE tenants
    ADD COLUMN email_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN webhook_enabled BOOLEAN NOT NULL DEFAULT true;

-- Backfill if needed (safe no-op if columns already defaulted)
UPDATE tenants
    SET email_enabled = true,
        webhook_enabled = true
    WHERE email_enabled IS NULL OR webhook_enabled IS NULL;