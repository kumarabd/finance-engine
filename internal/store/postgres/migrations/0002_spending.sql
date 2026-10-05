-- Canonical spending records have stable IDs independent of their editable dates.
-- Keep migration 0001 intact; legacy accounts/transactions remain readable.
CREATE TABLE finance.dimensions (
    owner_id text NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    kind text NOT NULL CHECK (kind IN ('category','tag','merchant')),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    aliases jsonb NOT NULL DEFAULT '[]',
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id,id)
);
CREATE UNIQUE INDEX dimensions_active_name ON finance.dimensions(owner_id,kind,lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE finance.evidence (
    owner_id text NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    title text NOT NULL,
    source_ref text NOT NULL,
    media_type text NOT NULL DEFAULT '',
    checksum text NOT NULL DEFAULT '',
    notes text NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id,id)
);

CREATE TABLE finance.spends (
    owner_id text NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_on date NOT NULL,
    kind text NOT NULL CHECK (kind IN ('expense','refund','transfer')),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 9007199254740991),
    currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    merchant_id uuid,
    description text NOT NULL DEFAULT '',
    account_ref text NOT NULL DEFAULT '',
    original_spend_id uuid,
    source text NOT NULL DEFAULT '',
    source_record_id text NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id,id),
    FOREIGN KEY (owner_id,merchant_id) REFERENCES finance.dimensions(owner_id,id),
    FOREIGN KEY (owner_id,original_spend_id) REFERENCES finance.spends(owner_id,id),
    CHECK (original_spend_id IS NULL OR (kind = 'refund' AND original_spend_id <> id)),
    CHECK ((source = '') = (source_record_id = ''))
);
-- A deleted record retains its source identity. Restore it instead of reimporting.
CREATE UNIQUE INDEX spends_source_identity ON finance.spends(owner_id,source,source_record_id) WHERE source <> '';
CREATE INDEX spends_date ON finance.spends(owner_id,occurred_on DESC,id DESC);
CREATE INDEX spends_merchant ON finance.spends(owner_id,merchant_id);
CREATE INDEX spends_refund ON finance.spends(owner_id,original_spend_id);

CREATE TABLE finance.spend_allocations (
    owner_id text NOT NULL,
    spend_id uuid NOT NULL,
    position integer NOT NULL,
    category_id uuid,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (owner_id,spend_id,position),
    FOREIGN KEY (owner_id,spend_id) REFERENCES finance.spends(owner_id,id),
    FOREIGN KEY (owner_id,category_id) REFERENCES finance.dimensions(owner_id,id)
);
CREATE INDEX allocation_category ON finance.spend_allocations(owner_id,category_id,spend_id);
CREATE TABLE finance.spend_tags (
    owner_id text NOT NULL,
    spend_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    PRIMARY KEY (owner_id,spend_id,tag_id),
    FOREIGN KEY (owner_id,spend_id) REFERENCES finance.spends(owner_id,id),
    FOREIGN KEY (owner_id,tag_id) REFERENCES finance.dimensions(owner_id,id)
);
CREATE TABLE finance.spend_evidence (
    owner_id text NOT NULL,
    spend_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    PRIMARY KEY (owner_id,spend_id,evidence_id),
    FOREIGN KEY (owner_id,spend_id) REFERENCES finance.spends(owner_id,id),
    FOREIGN KEY (owner_id,evidence_id) REFERENCES finance.evidence(owner_id,id)
);

CREATE TABLE finance.changes (
    owner_id text NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor_id text NOT NULL,
    operation text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid NOT NULL,
    before_data jsonb,
    after_data jsonb,
    PRIMARY KEY (id,occurred_at)
);
SELECT create_hypertable('finance.changes','occurred_at',if_not_exists => TRUE);
CREATE INDEX changes_entity ON finance.changes(owner_id,entity_type,entity_id,occurred_at DESC,id DESC);
CREATE TABLE finance.requests (
    owner_id text NOT NULL,
    key text NOT NULL,
    operation text NOT NULL,
    input_hash text NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id,key)
);
