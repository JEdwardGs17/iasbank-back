CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_number VARCHAR(20) NOT NULL UNIQUE,
    holder VARCHAR(150) NOT NULL,
    balance DECIMAL(19,2) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    daily_limit DECIMAL(19,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS transfers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_reference VARCHAR(50) NOT NULL UNIQUE,
    source_account_id VARCHAR(20) NOT NULL,
    destination_account_id VARCHAR(20) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    rejection_reason VARCHAR(30) NULL,
    processed_at DATETIME(6) NOT NULL,
    INDEX idx_transfers_daily_total (source_account_id, status, processed_at),
    INDEX idx_transfers_recent (processed_at)
);

CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) NOT NULL UNIQUE,
    event_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(50) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    UNIQUE KEY uk_outbox_events_aggregate (event_type, aggregate_id),
    INDEX idx_outbox_events_due (status, next_attempt_at)
);
