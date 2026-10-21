UPDATE refresh_tokens SET token = encode(sha256(convert_to(token, 'UTF8')), 'hex');
ALTER TABLE refresh_tokens RENAME COLUMN token TO token_hash;
ALTER TABLE refresh_tokens ALTER COLUMN token_hash TYPE VARCHAR(64);
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$');
DROP INDEX idx_refresh_tokens_token;
