-- Raw test API key: my-test-key-123
-- (SHA-256 hash is stored below, use the raw key in the X-API-Key header)
UPDATE api_keys
SET key_hash = 'fc4670b077ac29a2d622a354ecb803d55092bb3964fee64b2270f343c4270888'
WHERE id = '22222222-2222-2222-2222-222222222222';