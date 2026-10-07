-- Store only rotating keyed IP digests and counts, never messages or raw IPs.
CREATE TABLE IF NOT EXISTS contact_limits (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS contact_limits_expiry ON contact_limits(expires_at);
