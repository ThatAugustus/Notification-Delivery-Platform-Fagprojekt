INSERT INTO tenants (id, name, default_from_email)
VALUES ('11111111-1111-1111-1111-111111111111', 'Test Tenant', 'noreply@testtenant.com');

INSERT INTO api_keys (id, tenant_id, key_hash, prefix, name, active)
VALUES (
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    'a2e4ab0472c808a1ff2ce147ae4f6cd9ecd8bcc8a49c48350f97e6811ace7464',
    'test',
    'Test API Key',
    true
);