-- An outbox row that can never be published (for example a message the AMQP client refuses to encode)
-- is parked after a bounded number of attempts, so it no longer blocks every row after it.
-- Only nullable or defaulted columns are added; V1 rows start with 0 attempts and are not parked.
ALTER TABLE outbox ADD COLUMN attempts INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE outbox ADD COLUMN last_error VARCHAR(1000);
ALTER TABLE outbox ADD COLUMN parked_at TIMESTAMP(6) WITH TIME ZONE;
