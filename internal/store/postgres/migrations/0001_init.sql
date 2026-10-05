-- Extensions are provisioned by the database owner. The migration runner checks
-- they are enabled before applying this file.
CREATE SCHEMA IF NOT EXISTS finance;

CREATE TABLE finance.accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id TEXT NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('checking', 'savings', 'credit', 'cash', 'investment', 'loan', 'other')),
    currency TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id)
);
CREATE INDEX accounts_owner_id_idx ON finance.accounts (owner_id, created_at DESC);

-- The time column belongs in the primary key of a Timescale hypertable.
-- Positive amounts are inflows to the account; negative amounts are outflows.
CREATE TABLE finance.transactions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    owner_id TEXT NOT NULL,
    account_id UUID NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, posted_at),
    FOREIGN KEY (owner_id, account_id) REFERENCES finance.accounts (owner_id, id)
);
SELECT create_hypertable('finance.transactions', 'posted_at', if_not_exists => TRUE);
CREATE INDEX transactions_owner_posted_idx ON finance.transactions (owner_id, posted_at DESC, id DESC);
CREATE INDEX transactions_account_posted_idx ON finance.transactions (owner_id, account_id, posted_at DESC);

-- Kept separate from ledger records. Nothing writes embeddings automatically.
-- An index should be added only after an embedding model and dimension are chosen.
CREATE TABLE finance.semantic_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_id TEXT NOT NULL,
    model TEXT NOT NULL,
    embedding vector NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, source_type, source_id, model)
);
CREATE INDEX semantic_items_owner_idx ON finance.semantic_items (owner_id, source_type);
