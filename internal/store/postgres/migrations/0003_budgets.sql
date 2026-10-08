-- Budgets: a limit you set, and the notification when you cross it.
--
-- A budget is one concept, deliberately not two. "Tell me when dining exceeds
-- 20,000 this month" and "my dining budget is 20,000 this month" are the same
-- sentence, so there is no separate watch table pointing at a budget — the limit
-- and the alert live on the same row, and crossing it is recorded in
-- `budget_fires`. Rendering two overlapping things was the alternative, and it
-- had no advantage.
--
-- The limit is evaluated *where the data lands*: inside the same transaction that
-- writes the spend, after the row exists, so the total includes the write that
-- triggered it. There is no scheduler, no worker, and no new deployment — a
-- budget costs one aggregate query on the writes that could affect it, rather
-- than a poll that costs the same whether anything happened or not.
CREATE TABLE finance.budgets (
    owner_id text NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    -- The agent (or a person) names it, so a duplicate is a decision that
    -- collides on purpose rather than a slug guessed from free text.
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    -- Which shape of budget this is. Set from a literal by the operation that
    -- created it, never by the caller: the tool an agent picks already says
    -- whether it is budgeting a category or everything.
    kind text NOT NULL CHECK (kind IN ('category','total')),
    -- NULL means "all spending". A category budget without a category is
    -- unrepresentable, and so is a total budget that secretly has one.
    category_id uuid,
    -- A calendar period, not a rolling window: "this month" is the question
    -- people actually ask.
    period text NOT NULL CHECK (period IN ('week','month')),
    limit_minor bigint NOT NULL CHECK (limit_minor BETWEEN 1 AND 9007199254740991),
    currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- Alert when this percentage of the limit is reached. 100 fires only on
    -- going over; 80 warns with room left to act, which is the point of a budget
    -- for most people. Above 100 is allowed for a hard ceiling you want to hear
    -- about late.
    notify_at_percent smallint NOT NULL DEFAULT 100 CHECK (notify_at_percent BETWEEN 1 AND 200),
    -- Opaque callback credential minted by the waking side. Echoed back verbatim
    -- when a crossing is delivered; never interpreted here.
    callback_token text NOT NULL DEFAULT '',
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id,id),
    -- A category from another owner is unrepresentable, not merely filtered.
    FOREIGN KEY (owner_id,category_id) REFERENCES finance.dimensions (owner_id,id),
    CHECK ((kind = 'category') = (category_id IS NOT NULL))
);
CREATE UNIQUE INDEX budgets_active_name ON finance.budgets(owner_id,lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX budgets_live ON finance.budgets(owner_id,currency) WHERE deleted_at IS NULL AND active;

-- The fire log. Append-only: a crossing is a fact about the past and is never
-- edited. `delivered_at` is the only mutable column, set by the delivery step.
CREATE TABLE finance.budget_fires (
    owner_id text NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    budget_id uuid NOT NULL,
    -- The period the limit was crossed in ('2026-10'), so one crossing fires once
    -- however many spends land afterwards. This is the dedupe key and the unique
    -- index below is what enforces it.
    period_key text NOT NULL,
    -- What the total actually was, what the limit is, and the alert line that was
    -- crossed — so a fire explains itself without recomputation.
    observed_minor bigint NOT NULL,
    limit_minor bigint NOT NULL,
    alert_minor bigint NOT NULL,
    percent smallint NOT NULL,
    currency text NOT NULL,
    fired_at timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz,
    PRIMARY KEY (owner_id,id),
    FOREIGN KEY (owner_id,budget_id) REFERENCES finance.budgets (owner_id,id)
);
-- Fires once per period, not once per spend. The evaluate step inserts with
-- ON CONFLICT DO NOTHING against this index, so concurrent writes cannot
-- double-fire and nothing needs to lock. A budget that fired at 80% and then
-- went to 120% is one crossing of one line, not two events.
CREATE UNIQUE INDEX budget_fires_once_per_period ON finance.budget_fires(owner_id,budget_id,period_key);
CREATE INDEX budget_fires_undelivered ON finance.budget_fires(owner_id) WHERE delivered_at IS NULL;
