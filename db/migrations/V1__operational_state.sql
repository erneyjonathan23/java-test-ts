-- Operational state of the payment notifications integration (docs/design/SOLUTION_DESIGN.md, section 8).
-- Applied by the CD pipeline with Flyway (deploy-env.yml). Migrations are expand/contract: never drop or rename
-- a column in the same release that stops using it.
-- No payer personal data is stored in any table.

-- One row per business event: idempotency (FR4) and processing status for operations (FR7).
CREATE TABLE dbo.inbound_event (
    idempotency_key    NVARCHAR(160) NOT NULL,
    payment_key        NVARCHAR(80)  NOT NULL,
    country            CHAR(2)       NOT NULL,
    provider           VARCHAR(16)   NOT NULL,
    provider_txn_id    NVARCHAR(80)  NOT NULL,
    canonical_status   VARCHAR(16)   NOT NULL,
    status_changed_at  DATETIME2(3)  NOT NULL,
    canonical_event    NVARCHAR(MAX) NOT NULL,   -- JSON without PII, kept for replay
    processing_status  VARCHAR(20)   NOT NULL,   -- RECEIVED, PUBLISHED, APPLIED, SUPERSEDED, RECORDED,
                                                 -- NEEDS_ATTENTION, FAILED_PERMANENT, DEAD_LETTERED
    claimed_until      DATETIME2(3)  NULL,       -- intake lease while a delivery is publishing it
    last_error_code    VARCHAR(64)   NULL,
    correlation_id     VARCHAR(64)   NOT NULL,
    first_received_at  DATETIME2(3)  NOT NULL,
    updated_at         DATETIME2(3)  NOT NULL,
    CONSTRAINT pk_inbound_event PRIMARY KEY (idempotency_key),
    CONSTRAINT ck_inbound_event_country CHECK (country LIKE '[A-Z][A-Z]'),
    CONSTRAINT ck_inbound_event_json CHECK (ISJSON(canonical_event) = 1)
);
CREATE INDEX ix_inbound_event_txn ON dbo.inbound_event (provider, provider_txn_id);
CREATE INDEX ix_inbound_event_attention ON dbo.inbound_event (processing_status, country, updated_at)
    WHERE processing_status IN ('NEEDS_ATTENTION', 'FAILED_PERMANENT', 'DEAD_LETTERED');

-- One row per delivery attempt: operations can search by any provider id
-- (Andina changes notificacion_id on every retry).
CREATE TABLE dbo.inbound_attempt (
    attempt_id         BIGINT IDENTITY(1, 1) NOT NULL,
    provider           VARCHAR(16)   NOT NULL,
    provider_event_id  NVARCHAR(80)  NOT NULL,
    idempotency_key    NVARCHAR(160) NOT NULL,
    outcome            VARCHAR(16)   NOT NULL,   -- PUBLISHED, DUPLICATE
    correlation_id     VARCHAR(64)   NOT NULL,
    received_at        DATETIME2(3)  NOT NULL,
    CONSTRAINT pk_inbound_attempt PRIMARY KEY (attempt_id),
    CONSTRAINT fk_inbound_attempt_event FOREIGN KEY (idempotency_key) REFERENCES dbo.inbound_event (idempotency_key)
);
CREATE INDEX ix_attempt_provider_event ON dbo.inbound_attempt (provider, provider_event_id);
CREATE INDEX ix_attempt_correlation ON dbo.inbound_attempt (correlation_id);

-- Latest known state per payment: drives ordering (FR5, ADR-0003) and the Salesforce sync (ADR-0001).
CREATE TABLE dbo.payment_state (
    payment_key        NVARCHAR(80)  NOT NULL,
    country            CHAR(2)       NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    status_changed_at  DATETIME2(3)  NOT NULL,
    last_event_id      NVARCHAR(80)  NOT NULL,
    policy_number      VARCHAR(20)   NULL,
    amount             DECIMAL(16, 2) NOT NULL,
    currency           CHAR(3)       NOT NULL,
    amount_refunded    DECIMAL(16, 2) NULL,
    sync_status        VARCHAR(20)   NOT NULL,   -- DIRTY, SYNCED, NEEDS_ATTENTION, FAILED_PERMANENT
    sync_attempts      INT           NOT NULL CONSTRAINT df_payment_state_attempts DEFAULT 0,
    next_sync_at       DATETIME2(3)  NULL,
    synced_at          DATETIME2(3)  NULL,
    row_version        ROWVERSION    NOT NULL,   -- optimistic concurrency for the precedence rule
    CONSTRAINT pk_payment_state PRIMARY KEY (payment_key),
    CONSTRAINT ck_payment_state_status CHECK (status IN ('PENDING', 'CAPTURED', 'FAILED', 'REFUNDED'))
);
CREATE INDEX ix_payment_state_dirty ON dbo.payment_state (next_sync_at) WHERE sync_status = 'DIRTY';
