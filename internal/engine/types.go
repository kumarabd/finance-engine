package engine

import (
	"encoding/json"
	"time"
)

type Principal struct{ Owner, Actor string }
type Meta struct {
	IdempotencyKey string `json:"idempotency_key" jsonschema:"Unique request key, 1-128 characters. Reuse only to retry exactly the same operation and input."`
}
type Versioned struct {
	ID              string `json:"id" jsonschema:"Record UUID."`
	ExpectedVersion int64  `json:"expected_version" jsonschema:"Version last read; stale edits return conflict."`
}
type GetInput struct {
	ID string `json:"id"`
}
type PageInput struct {
	Limit  int    `json:"limit,omitempty" jsonschema:"Page size, default 50, maximum 200."`
	Offset int    `json:"offset,omitempty" jsonschema:"Zero-based offset."`
	State  string `json:"state,omitempty" jsonschema:"active (default), deleted, or all."`
	Search string `json:"search,omitempty" jsonschema:"Case-insensitive literal substring."`
}
type Page[T any] struct {
	Items      []T  `json:"items"`
	Total      int  `json:"total"`
	NextOffset *int `json:"next_offset"`
}
type Allocation struct {
	CategoryID  string `json:"category_id,omitempty" jsonschema:"Category UUID; omit for uncategorized."`
	AmountMinor int64  `json:"amount_minor" jsonschema:"Positive integer minor units. All allocations must sum exactly to the spend amount."`
}
type SpendInput struct {
	OccurredOn      string       `json:"occurred_on" jsonschema:"Calendar date YYYY-MM-DD; no timezone conversion."`
	Kind            string       `json:"kind" jsonschema:"expense, refund, or transfer. Transfers are excluded from spending analysis."`
	AmountMinor     int64        `json:"amount_minor" jsonschema:"Positive amount in currency minor units, maximum 9007199254740991."`
	Currency        string       `json:"currency" jsonschema:"Three uppercase letters. Currencies are never silently combined or converted."`
	MerchantID      string       `json:"merchant_id,omitempty"`
	Description     string       `json:"description,omitempty"`
	AccountRef      string       `json:"account_ref,omitempty" jsonschema:"Optional supporting account reference; no account setup required."`
	OriginalSpendID string       `json:"original_spend_id,omitempty" jsonschema:"Optional expense UUID for a refund, in the same currency."`
	Source          string       `json:"source,omitempty" jsonschema:"Optional source namespace, paired with source_record_id for duplicate prevention."`
	SourceRecordID  string       `json:"source_record_id,omitempty"`
	Allocations     []Allocation `json:"allocations,omitempty" jsonschema:"Category splits. Omit for one uncategorized allocation."`
	TagIDs          []string     `json:"tag_ids,omitempty"`
	EvidenceIDs     []string     `json:"evidence_ids,omitempty"`
}
type Spend struct {
	SpendInput
	ID        string     `json:"id"`
	Version   int64      `json:"version"`
	DeletedAt *time.Time `json:"deleted_at"`
	CreatedAt time.Time  `json:"created_at"`
	UpdatedAt time.Time  `json:"updated_at"`
}
type CreateSpendInput struct {
	Meta
	Spend SpendInput `json:"spend"`
}
type UpdateSpendInput struct {
	Meta
	Versioned
	Spend SpendInput `json:"spend" jsonschema:"Complete replacement of editable fields. Omitted optional fields are cleared."`
}
type LifecycleInput struct {
	Meta
	Versioned
}
type BulkCreateInput struct {
	Meta
	Spends []SpendInput `json:"spends" jsonschema:"1-100 records; the entire batch succeeds or rolls back."`
}
type SpendPatch struct {
	OccurredOn      *string       `json:"occurred_on,omitempty"`
	Kind            *string       `json:"kind,omitempty"`
	AmountMinor     *int64        `json:"amount_minor,omitempty"`
	Currency        *string       `json:"currency,omitempty"`
	MerchantID      *string       `json:"merchant_id,omitempty" jsonschema:"Empty string removes merchant."`
	Description     *string       `json:"description,omitempty"`
	AccountRef      *string       `json:"account_ref,omitempty"`
	OriginalSpendID *string       `json:"original_spend_id,omitempty"`
	CategoryID      *string       `json:"category_id,omitempty" jsonschema:"Replace all splits with this category; empty string means uncategorized."`
	Allocations     *[]Allocation `json:"allocations,omitempty"`
	TagIDs          *[]string     `json:"tag_ids,omitempty" jsonschema:"Replace all tags, including empty array to clear."`
	AddTagIDs       []string      `json:"add_tag_ids,omitempty"`
	RemoveTagIDs    []string      `json:"remove_tag_ids,omitempty"`
	EvidenceIDs     *[]string     `json:"evidence_ids,omitempty" jsonschema:"Replace evidence links; empty array detaches all."`
}
type BulkUpdateInput struct {
	Meta
	Records []Versioned `json:"records"`
	Patch   SpendPatch  `json:"patch"`
}
type BulkLifecycleInput struct {
	Meta
	Records []Versioned `json:"records"`
}
type SpendsResult struct {
	Items []Spend `json:"items"`
}

type DimensionInput struct {
	Name    string   `json:"name"`
	Aliases []string `json:"aliases,omitempty" jsonschema:"Merchant aliases only. Categories and tags use an empty list."`
}
type Dimension struct {
	DimensionInput
	ID        string     `json:"id"`
	Kind      string     `json:"kind"`
	Version   int64      `json:"version"`
	DeletedAt *time.Time `json:"deleted_at"`
	CreatedAt time.Time  `json:"created_at"`
	UpdatedAt time.Time  `json:"updated_at"`
}
type CreateDimensionInput struct {
	Meta
	DimensionInput
}
type UpdateDimensionInput struct {
	Meta
	Versioned
	DimensionInput
}
type DeleteDimensionInput struct {
	Meta
	Versioned
	ReplacementID      string `json:"replacement_id,omitempty" jsonschema:"Required when referenced. Reassign all current references, including deleted spends, atomically."`
	ReplacementVersion int64  `json:"replacement_version,omitempty"`
}
type MergeDimensionInput struct {
	Meta
	Versioned
	TargetID      string `json:"target_id"`
	TargetVersion int64  `json:"target_version"`
}
type MergeResult struct {
	Source        Dimension `json:"source"`
	Target        Dimension `json:"target"`
	ChangedSpends int       `json:"changed_spends"`
}

type EvidenceInput struct {
	Title     string `json:"title"`
	SourceRef string `json:"source_ref" jsonschema:"Opaque document ID or URI. Engine stores the reference and never fetches it."`
	MediaType string `json:"media_type,omitempty"`
	Checksum  string `json:"checksum,omitempty"`
	Notes     string `json:"notes,omitempty"`
}
type Evidence struct {
	EvidenceInput
	ID        string     `json:"id"`
	Version   int64      `json:"version"`
	DeletedAt *time.Time `json:"deleted_at"`
	CreatedAt time.Time  `json:"created_at"`
	UpdatedAt time.Time  `json:"updated_at"`
}
type CreateEvidenceInput struct {
	Meta
	EvidenceInput
}
type UpdateEvidenceInput struct {
	Meta
	Versioned
	EvidenceInput
}
type DeleteEvidenceInput struct {
	Meta
	Versioned
	Detach bool `json:"detach,omitempty" jsonschema:"Explicitly detach all current links before deleting. Otherwise linked evidence returns conflict."`
}
type EvidenceLinkInput struct {
	Meta
	Versioned
	EvidenceID string `json:"evidence_id"`
}

type Filter struct {
	From            string   `json:"from,omitempty" jsonschema:"Inclusive calendar date YYYY-MM-DD."`
	To              string   `json:"to,omitempty" jsonschema:"Inclusive calendar date YYYY-MM-DD."`
	Kind            string   `json:"kind,omitempty"`
	Currency        string   `json:"currency,omitempty"`
	MerchantID      string   `json:"merchant_id,omitempty"`
	CategoryID      string   `json:"category_id,omitempty"`
	Uncategorized   bool     `json:"uncategorized,omitempty"`
	TagIDs          []string `json:"tag_ids,omitempty" jsonschema:"All supplied tags must match."`
	AccountRef      string   `json:"account_ref,omitempty"`
	EvidenceID      string   `json:"evidence_id,omitempty"`
	OriginalSpendID string   `json:"original_spend_id,omitempty"`
	MinAmount       *int64   `json:"min_amount_minor,omitempty"`
	MaxAmount       *int64   `json:"max_amount_minor,omitempty"`
	Search          string   `json:"search,omitempty" jsonschema:"Literal substring across description, merchant name, aliases and account reference."`
	State           string   `json:"state,omitempty" jsonschema:"active (default), deleted, or all."`
}
type SearchInput struct {
	Filter
	Limit  int    `json:"limit,omitempty"`
	Offset int    `json:"offset,omitempty"`
	Sort   string `json:"sort,omitempty" jsonschema:"date_desc (default), date_asc, amount_desc, amount_asc. Amount sorting requires a currency filter."`
}
type ExportInput struct{ SearchInput }
type ExportResult struct {
	CSV        string `json:"csv"`
	Total      int    `json:"total"`
	NextOffset *int   `json:"next_offset"`
}
type AnalysisInput struct {
	Filter
	GroupBy     string `json:"group_by,omitempty" jsonschema:"total (default), category, merchant, tag, day, week, or month. Each group remains separated by currency."`
	CompareFrom string `json:"compare_from,omitempty"`
	CompareTo   string `json:"compare_to,omitempty"`
}
type Bucket struct {
	Currency     string `json:"currency"`
	Key          string `json:"key"`
	Label        string `json:"label"`
	ExpenseMinor string `json:"expense_minor" jsonschema:"Exact integer string; aggregate totals can exceed JavaScript's safe integer range."`
	RefundMinor  string `json:"refund_minor"`
	NetMinor     string `json:"net_minor"`
	Count        int64  `json:"count"`
}
type Analysis struct {
	GroupsOverlap bool     `json:"groups_overlap"`
	Current       []Bucket `json:"current"`
	Comparison    []Bucket `json:"comparison"`
	GroupBy       string   `json:"group_by"`
}
type HistoryInput struct {
	EntityType string `json:"entity_type" jsonschema:"spend, category, tag, merchant, or evidence."`
	ID         string `json:"id"`
	Limit      int    `json:"limit,omitempty"`
	Offset     int    `json:"offset,omitempty"`
}
type Change struct {
	ID         string          `json:"id"`
	OccurredAt time.Time       `json:"occurred_at"`
	Actor      string          `json:"actor"`
	Operation  string          `json:"operation"`
	Before     json.RawMessage `json:"before"`
	After      json.RawMessage `json:"after"`
}

// Budget — a limit you set on spending, and the notification when you cross it.
// One concept, not two: the limit and the alert live on the same row, because
// "tell me when dining exceeds 20,000" and "my dining budget is 20,000" are the
// same sentence.
//
// The limit is evaluated when a spend is written, not on a schedule, so a
// crossing is detected as it happens and costs nothing while nothing is
// happening. Crossings accumulate in `budget_fires`; delivering one is the waking
// side's job.
type Budget struct {
	ID              string    `json:"id"`
	Version         int64     `json:"version"`
	Name            string    `json:"name" jsonschema:"Unique per user, ignoring case. Name it deliberately, so a duplicate is a collision you meant rather than a guess."`
	Kind            string    `json:"kind" jsonschema:"category when the budget covers one category, total when it covers all spending."`
	CategoryID      string    `json:"category_id,omitempty" jsonschema:"Empty for a budget on all spending."`
	CategoryName    string    `json:"category_name,omitempty"`
	Period          string    `json:"period" jsonschema:"week or month — a calendar period, not a rolling window."`
	LimitMinor      int64     `json:"limit_minor" jsonschema:"The limit, in currency minor units."`
	Currency        string    `json:"currency" jsonschema:"A limit is meaningless without a currency, and currencies are never combined."`
	NotifyAtPercent int       `json:"notify_at_percent" jsonschema:"Alert when this percentage of the limit is reached. 100 fires only on going over; 80 warns with room left to act."`
	CallbackToken   string    `json:"callback_token,omitempty" jsonschema:"Opaque credential minted by the waking side. Echoed back when a crossing is delivered; never interpreted here."`
	Active          bool      `json:"active"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}
type BudgetInput struct {
	Name            string `json:"name"`
	CategoryID      string `json:"category_id,omitempty" jsonschema:"Omit to budget all spending."`
	Period          string `json:"period" jsonschema:"week or month."`
	LimitMinor      int64  `json:"limit_minor"`
	Currency        string `json:"currency"`
	NotifyAtPercent int    `json:"notify_at_percent,omitempty" jsonschema:"Defaults to 100."`
	CallbackToken   string `json:"callback_token,omitempty"`
}
type CreateBudgetInput struct {
	Meta
	Budget BudgetInput `json:"budget"`
}
type UpdateBudgetInput struct {
	Versioned
	Budget BudgetInput `json:"budget" jsonschema:"Category and kind are fixed at creation; delete and recreate to move a budget to another category."`
}
type ListBudgetsInput struct {
	IncludeInactive bool `json:"include_inactive,omitempty"`
	Limit           int  `json:"limit,omitempty"`
	Offset          int  `json:"offset,omitempty"`
}
type BudgetsResult struct {
	Items []Budget `json:"items"`
}

// BudgetStatus — what a budgets screen renders: how much of the current period's
// limit has been used, what is left, and whether the alert line has been crossed.
// Computed from live spending, never from the fire log, so a budget is true about
// the ledger whether or not anybody heard about it.
type BudgetStatus struct {
	Budget
	PeriodKey      string `json:"period_key" jsonschema:"The period this status describes, e.g. 2026-10."`
	PeriodFrom     string `json:"period_from"`
	PeriodTo       string `json:"period_to"`
	SpentMinor     int64  `json:"spent_minor"`
	RemainingMinor int64  `json:"remaining_minor" jsonschema:"Never negative: an exceeded budget reports zero left, not a negative allowance."`
	Percent        int    `json:"percent" jsonschema:"May exceed 100 when the budget is over."`
	AlertMinor     int64  `json:"alert_minor" jsonschema:"The amount the alert fires at, derived from limit_minor and notify_at_percent."`
	Alerted        bool   `json:"alerted"`
}
type StatusInput struct {
	IncludeInactive bool `json:"include_inactive,omitempty"`
}
type StatusResult struct {
	Items []BudgetStatus `json:"items"`
}

// BudgetFire — one recorded crossing. Append-only: a crossing is a fact about the
// past, and `DeliveredAt` is the only field delivery ever writes.
type BudgetFire struct {
	ID            string     `json:"id"`
	BudgetID      string     `json:"budget_id"`
	Name          string     `json:"name"`
	PeriodKey     string     `json:"period_key" jsonschema:"The period the limit was crossed in, e.g. 2026-10. One crossing fires once, however many spends follow."`
	ObservedMinor int64      `json:"observed_minor" jsonschema:"What the total actually was at the crossing, so a fire explains itself without recomputation."`
	LimitMinor    int64      `json:"limit_minor"`
	AlertMinor    int64      `json:"alert_minor" jsonschema:"The alert line that was crossed."`
	Percent       int        `json:"percent"`
	Currency      string     `json:"currency"`
	FiredAt       time.Time  `json:"fired_at"`
	DeliveredAt   *time.Time `json:"delivered_at,omitempty"`
}
type ListFiresInput struct {
	BudgetID string `json:"budget_id,omitempty"`
	Limit    int    `json:"limit,omitempty"`
	Offset   int    `json:"offset,omitempty"`
}
type FiresResult struct {
	Items []BudgetFire `json:"items"`
}

// Event is one thing this engine noticed, in the shape mcp-hub's delivery loop
// reads. See events.go for why these two operations exist and why they are named
// for a mechanism rather than a use case.
type Event struct {
	EventKey  string `json:"event_key" jsonschema:"What is being watched. Subscribers are registered against this, so it stays stable across occurrences of the same thing."`
	EventID   string `json:"event_id" jsonschema:"This occurrence, a durable id rather than a timestamp, so re-delivering the same one is detectable."`
	Objective string `json:"objective" jsonschema:"What happened, in one sentence, for whoever gets woken about it."`
	Why       string `json:"why,omitempty"`
}
type EventsResult struct {
	Items []Event `json:"items"`
}
type AckEventsInput struct {
	EventIDs []string `json:"event_ids" jsonschema:"The event ids that were handed over. Acks are idempotent, so re-sending one is harmless."`
}
type AckEventsResult struct {
	Acknowledged int `json:"acknowledged"`
}
