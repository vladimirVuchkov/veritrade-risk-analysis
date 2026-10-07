CREATE TABLE filings (
    id             UUID                        NOT NULL PRIMARY KEY,
    company_name   VARCHAR(200)                NOT NULL,
    title          VARCHAR(300)                NOT NULL,
    content        CLOB                        NOT NULL,
    status         VARCHAR(20)                 NOT NULL,
    submitted_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    failure_reason VARCHAR(1000),
    version        BIGINT                      NOT NULL DEFAULT 0,
    CONSTRAINT ck_filings_status CHECK (status IN ('SUBMITTED', 'ANALYZING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_filings_submitted_at ON filings (submitted_at DESC);

-- One row per event to publish. id is the eventId of the envelope stored in payload.
CREATE TABLE outbox (
    id             UUID                        NOT NULL PRIMARY KEY,
    routing_key    VARCHAR(100)                NOT NULL,
    correlation_id VARCHAR(128)                NOT NULL,
    payload        CLOB                        NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX ix_outbox_unpublished ON outbox (published_at, created_at);
