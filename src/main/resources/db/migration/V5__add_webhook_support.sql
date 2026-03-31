ALTER TABLE notifications ADD COLUMN webhook_url VARCHAR(2048);

ALTER TABLE tenants ADD COLUMN webhook_secret VARCHAR(255);

UPDATE tenants SET webhook_secret = 'test-webhook-secret-123'
WHERE id = '11111111-1111-1111-1111-111111111111';