package engine

import (
	"context"
	"crypto/rand"
	"encoding/csv"
	"encoding/json"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/kumarabd/finance-engine/internal/store/postgres"
)

func setup(t *testing.T) (*Service, Principal) {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL is required for PostgreSQL integration tests")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if err = postgres.Migrate(context.Background(), pool); err != nil {
		t.Fatal(err)
	}
	return New(pool), Principal{Owner: "test-" + rand.Text(), Actor: "test-agent"}
}
func meta() Meta { return Meta{IdempotencyKey: rand.Text()} }
func invoke[T any](t *testing.T, s *Service, p Principal, op string, in any) T {
	t.Helper()
	raw, err := json.Marshal(in)
	if err != nil {
		t.Fatal(err)
	}
	out, err := s.Execute(context.Background(), p, op, raw)
	if err != nil {
		t.Fatalf("%s: %v", op, err)
	}
	var shape any
	if err = json.Unmarshal(out, &shape); err != nil {
		t.Fatal(err)
	}
	schema, err := s.operations[op].OutputSchema.Resolve(nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = schema.Validate(shape); err != nil {
		t.Fatalf("%s output contract: %v", op, err)
	}
	var value T
	if err = json.Unmarshal(out, &value); err != nil {
		t.Fatal(err)
	}
	return value
}
func reject(t *testing.T, s *Service, p Principal, op string, in any, code string) {
	t.Helper()
	raw, _ := json.Marshal(in)
	_, err := s.Execute(context.Background(), p, op, raw)
	if err == nil || PublicError(err).Code != code {
		t.Fatalf("%s: expected %s, got %v", op, code, err)
	}
}
func dim(t *testing.T, s *Service, p Principal, kind, name string) Dimension {
	return invoke[Dimension](t, s, p, kind+"_create", CreateDimensionInput{Meta: meta(), DimensionInput: DimensionInput{Name: name}})
}
func spendInput(amount int64) SpendInput {
	return SpendInput{OccurredOn: "2026-10-04", Kind: "expense", AmountMinor: amount, Currency: "USD"}
}
func create(t *testing.T, s *Service, p Principal, input SpendInput) Spend {
	return invoke[Spend](t, s, p, "spends_create", CreateSpendInput{Meta: meta(), Spend: input})
}
func v(s Spend) Versioned { return Versioned{ID: s.ID, ExpectedVersion: s.Version} }
func TestRegistryAndInputValidation(t *testing.T) {
	s := New(nil)
	if len(s.Operations()) != 42 {
		t.Fatalf("operations=%d", len(s.Operations()))
	}
	for _, op := range s.Operations() {
		if op.InputSchema == nil || op.OutputSchema == nil {
			t.Fatal(op.Name)
		}
	}
	p := Principal{Owner: "test"}
	reject(t, s, p, "spends_create", map[string]any{"idempotency_key": "k"}, "invalid_input")
	reject(t, s, p, "spends_get", map[string]any{"id": "x", "owner_id": "forged"}, "invalid_input")
	reject(t, s, Principal{}, "spends_get", GetInput{ID: "x"}, "unauthorized")
	reject(t, s, p, "unknown", map[string]any{}, "not_found")
	spec := s.OpenAPI()
	if len(spec["paths"].(map[string]any)) != 42 {
		t.Fatal("HTTP parity")
	}
}

func TestHistorySnapshotContract(t *testing.T) {
	schema, err := New(nil).operations["history_list"].OutputSchema.Resolve(nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, snapshots := range []string{
		`"before":null,"after":{"amount_minor":450,"currency":"USD"}`,
		`"before":{"amount_minor":450},"after":{"amount_minor":500}`,
	} {
		var result any
		err = json.Unmarshal([]byte(`{"items":[{"id":"change","occurred_at":"2026-10-04T00:00:00Z","actor":"agent","operation":"spends_update",`+snapshots+`}],"total":1,"next_offset":null}`), &result)
		if err != nil {
			t.Fatal(err)
		}
		if err = schema.Validate(result); err != nil {
			t.Fatal(err)
		}
	}
}
func TestSpendingAnalysisAndIsolation(t *testing.T) {
	s, p := setup(t)
	groceries := dim(t, s, p, "categories", "Groceries")
	household := dim(t, s, p, "categories", "Household")
	travel := dim(t, s, p, "tags", "Travel")
	shared := dim(t, s, p, "tags", "Shared")
	merchant := dim(t, s, p, "merchants", "Local Store")
	evidence := invoke[Evidence](t, s, p, "evidence_create", CreateEvidenceInput{Meta: meta(), EvidenceInput: EvidenceInput{Title: "Receipt", SourceRef: "document:123"}})
	data := spendInput(10000)
	data.MerchantID = merchant.ID
	data.Description = "100% real_purchase"
	data.Allocations = []Allocation{{groceries.ID, 6000}, {household.ID, 4000}}
	data.TagIDs = []string{travel.ID, shared.ID}
	data.EvidenceIDs = []string{evidence.ID}
	original := create(t, s, p, data)
	refund := spendInput(2000)
	refund.Kind = "refund"
	refund.OriginalSpendID = original.ID
	refund.Allocations = []Allocation{{groceries.ID, 2000}}
	refund.OccurredOn = "2026-10-05"
	create(t, s, p, refund)
	transfer := spendInput(50000)
	transfer.Kind = "transfer"
	create(t, s, p, transfer)
	otherCurrency := spendInput(40000)
	otherCurrency.Currency = "INR"
	create(t, s, p, otherCurrency)
	result := invoke[Analysis](t, s, p, "spending_analyze", AnalysisInput{})
	if len(result.Current) != 2 || result.Current[0].Currency != "INR" || result.Current[0].NetMinor != "40000" || result.Current[1].NetMinor != "8000" {
		t.Fatalf("totals: %+v", result)
	}
	category := invoke[Analysis](t, s, p, "spending_analyze", AnalysisInput{Filter: Filter{CategoryID: groceries.ID}})
	if len(category.Current) != 1 || category.Current[0].ExpenseMinor != "6000" || category.Current[0].RefundMinor != "2000" || category.Current[0].NetMinor != "4000" {
		t.Fatalf("split filtering: %+v", category)
	}
	breakdown := invoke[Analysis](t, s, p, "spending_analyze", AnalysisInput{Filter: Filter{Currency: "USD"}, GroupBy: "category"})
	if len(breakdown.Current) != 2 {
		t.Fatalf("category groups: %+v", breakdown)
	}
	tags := invoke[Analysis](t, s, p, "spending_analyze", AnalysisInput{Filter: Filter{TagIDs: []string{travel.ID}}, GroupBy: "tag"})
	if !tags.GroupsOverlap || len(tags.Current) != 2 {
		t.Fatalf("tag groups: %+v", tags)
	}
	compared := invoke[Analysis](t, s, p, "spending_analyze", AnalysisInput{Filter: Filter{Currency: "USD", From: "2026-10-05", To: "2026-10-05"}, CompareFrom: "2026-10-04", CompareTo: "2026-10-04", GroupBy: "day"})
	if compared.Current[0].NetMinor != "-2000" || compared.Comparison[0].NetMinor != "10000" {
		t.Fatalf("comparison: %+v", compared)
	}
	found := invoke[Page[Spend]](t, s, p, "spends_search", SearchInput{Filter: Filter{Search: "% real_", TagIDs: []string{travel.ID, shared.ID}, EvidenceID: evidence.ID}})
	if found.Total != 1 || found.Items[0].ID != original.ID {
		t.Fatalf("filters: %+v", found)
	}
	missing := invoke[Page[Spend]](t, s, p, "spends_search", SearchInput{Filter: Filter{Search: "not_there%"}})
	if missing.Total != 0 {
		t.Fatal("LIKE wildcards were not escaped")
	}
	first := invoke[Page[Spend]](t, s, p, "spends_search", SearchInput{Limit: 2})
	second := invoke[Page[Spend]](t, s, p, "spends_search", SearchInput{Limit: 2, Offset: *first.NextOffset})
	if first.Total != 4 || len(second.Items) != 2 || second.NextOffset != nil || first.Items[0].ID == second.Items[0].ID {
		t.Fatal("pagination")
	}
	reject(t, s, p, "spends_search", SearchInput{Sort: "amount_desc"}, "invalid_input")
	other := Principal{Owner: p.Owner + "-other"}
	reject(t, s, other, "spends_get", GetInput{ID: original.ID}, "not_found")
	reject(t, s, other, "spends_create", CreateSpendInput{Meta: meta(), Spend: data}, "not_found")
	empty := invoke[Analysis](t, s, other, "spending_analyze", AnalysisInput{})
	if len(empty.Current) != 0 {
		t.Fatal("cross-owner analysis")
	}
	bad := data
	bad.Allocations = []Allocation{{groceries.ID, 9000}}
	reject(t, s, p, "spends_create", CreateSpendInput{Meta: meta(), Spend: bad}, "invalid_input")
	bad = data
	bad.MerchantID = travel.ID
	reject(t, s, p, "spends_create", CreateSpendInput{Meta: meta(), Spend: bad}, "not_found")
	data = spendInput(100)
	data.Description = "=HYPERLINK(\"bad\")"
	create(t, s, p, data)
	export := invoke[ExportResult](t, s, p, "spends_export", ExportInput{SearchInput: SearchInput{Filter: Filter{Search: "HYPERLINK"}}})
	csvRows, err := csv.NewReader(strings.NewReader(export.CSV)).ReadAll()
	if err != nil || len(csvRows) != 2 || !strings.HasPrefix(csvRows[1][7], "'=") {
		t.Fatalf("CSV protection: %v %q", err, export.CSV)
	}
}
func TestIdempotencyConcurrencyAndAtomicBulk(t *testing.T) {
	s, p := setup(t)
	input := CreateSpendInput{Meta: meta(), Spend: spendInput(100)}
	input.Spend.Source = "statement"
	input.Spend.SourceRecordID = "row-1"
	raw, _ := json.Marshal(input)
	const workers = 8
	var wg sync.WaitGroup
	errs := make(chan error, workers)
	ids := make(chan string, workers)
	for i := 0; i < workers; i++ {
		wg.Go(func() {
			result, err := s.Execute(context.Background(), p, "spends_create", raw)
			if err != nil {
				errs <- err
				return
			}
			var spend Spend
			if err = json.Unmarshal(result, &spend); err != nil {
				errs <- err
				return
			}
			ids <- spend.ID
		})
	}
	wg.Wait()
	close(errs)
	close(ids)
	for err := range errs {
		t.Fatal(err)
	}
	id := ""
	for current := range ids {
		if id != "" && current != id {
			t.Fatal("duplicate retry")
		}
		id = current
	}
	before := invoke[Spend](t, s, p, "spends_get", GetInput{ID: id})
	changed := input
	changed.Spend.AmountMinor = 200
	reject(t, s, p, "spends_create", changed, "conflict")
	duplicate := input
	duplicate.Meta = meta()
	reject(t, s, p, "spends_create", duplicate, "duplicate")
	// Two writers using one version: exactly one succeeds.
	errs = make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Go(func() {
			update := UpdateSpendInput{Meta: meta(), Versioned: v(before), Spend: spendInput(200)}
			body, _ := json.Marshal(update)
			_, err := s.Execute(context.Background(), p, "spends_update", body)
			errs <- err
		})
	}
	wg.Wait()
	close(errs)
	success, conflicts := 0, 0
	for err := range errs {
		if err == nil {
			success++
		} else if code := PublicError(err).Code; code == "conflict" || code == "busy" {
			// The loser sees either a stale version or, under real contention, a retryable serialization failure.
			conflicts++
		} else {
			t.Fatal(err)
		}
	}
	if success != 1 || conflicts != 1 {
		t.Fatalf("success=%d conflict=%d", success, conflicts)
	}
	a := invoke[Spend](t, s, p, "spends_get", GetInput{ID: id})
	b := create(t, s, p, spendInput(300))
	tag := dim(t, s, p, "tags", "Review")
	patch := SpendPatch{AddTagIDs: []string{tag.ID}}
	reject(t, s, p, "spends_bulk_update", BulkUpdateInput{Meta: meta(), Records: []Versioned{v(a), {ID: b.ID, ExpectedVersion: 99}}, Patch: patch}, "conflict")
	unchanged := invoke[Spend](t, s, p, "spends_get", GetInput{ID: a.ID})
	if unchanged.Version != a.Version || len(unchanged.TagIDs) != 0 {
		t.Fatal("partial bulk update committed")
	}
	bad := spendInput(10)
	bad.AmountMinor = -1
	reject(t, s, p, "spends_bulk_create", BulkCreateInput{Meta: meta(), Spends: []SpendInput{spendInput(10), bad}}, "invalid_input")
	records := invoke[Page[Spend]](t, s, p, "spends_search", SearchInput{})
	if records.Total != 2 {
		t.Fatalf("partial create committed: %d", records.Total)
	}
	bulk := BulkUpdateInput{Meta: meta(), Records: []Versioned{v(a), v(b)}, Patch: patch}
	updated := invoke[SpendsResult](t, s, p, "spends_bulk_update", bulk)
	repeated := invoke[SpendsResult](t, s, p, "spends_bulk_update", bulk)
	if len(updated.Items) != 2 || repeated.Items[0].Version != updated.Items[0].Version {
		t.Fatal("bulk retry")
	}
	cleared := invoke[SpendsResult](t, s, p, "spends_bulk_update", BulkUpdateInput{Meta: meta(), Records: []Versioned{v(updated.Items[0])}, Patch: SpendPatch{RemoveTagIDs: []string{tag.ID}}})
	if len(cleared.Items[0].TagIDs) != 0 {
		t.Fatal("tag removal")
	}
	h := invoke[Page[Change]](t, s, p, "history_list", HistoryInput{EntityType: "spend", ID: a.ID})
	if h.Total != 4 || h.Items[0].Actor != "test-agent" {
		t.Fatalf("audit: %+v", h)
	}
	var previous Spend
	if err := json.Unmarshal(h.Items[0].Before, &previous); err != nil || len(previous.TagIDs) != 1 {
		t.Fatal("missing before state")
	}
}
func TestOrganizationAndEvidenceLifecycle(t *testing.T) {
	s, p := setup(t)
	a := dim(t, s, p, "categories", "Food")
	b := dim(t, s, p, "categories", "Groceries")
	x := dim(t, s, p, "tags", "Trip")
	y := dim(t, s, p, "tags", "Travel")
	m1 := dim(t, s, p, "merchants", "STORE 123")
	m2 := dim(t, s, p, "merchants", "Store")
	e := invoke[Evidence](t, s, p, "evidence_create", CreateEvidenceInput{Meta: meta(), EvidenceInput: EvidenceInput{Title: "Receipt", SourceRef: "doc:1"}})
	input := spendInput(100)
	input.Allocations = []Allocation{{a.ID, 40}, {b.ID, 60}}
	input.TagIDs = []string{x.ID, y.ID}
	input.MerchantID = m1.ID
	input.EvidenceIDs = []string{e.ID}
	spend := create(t, s, p, input)
	reject(t, s, p, "categories_delete", DeleteDimensionInput{Meta: meta(), Versioned: Versioned{a.ID, a.Version}}, "conflict")
	merged := invoke[MergeResult](t, s, p, "categories_merge", MergeDimensionInput{Meta: meta(), Versioned: Versioned{a.ID, a.Version}, TargetID: b.ID, TargetVersion: b.Version})
	if merged.ChangedSpends != 1 || merged.Source.DeletedAt == nil {
		t.Fatal("category merge")
	}
	spend = invoke[Spend](t, s, p, "spends_get", GetInput{ID: spend.ID})
	if len(spend.Allocations) != 1 || spend.Allocations[0].CategoryID != b.ID || spend.Allocations[0].AmountMinor != 100 {
		t.Fatal("merge split amounts")
	}
	invoke[MergeResult](t, s, p, "tags_merge", MergeDimensionInput{Meta: meta(), Versioned: Versioned{x.ID, x.Version}, TargetID: y.ID, TargetVersion: y.Version})
	spend = invoke[Spend](t, s, p, "spends_get", GetInput{ID: spend.ID})
	if len(spend.TagIDs) != 1 || spend.TagIDs[0] != y.ID {
		t.Fatal("merge tags")
	}
	merchants := invoke[MergeResult](t, s, p, "merchants_merge", MergeDimensionInput{Meta: meta(), Versioned: Versioned{m1.ID, m1.Version}, TargetID: m2.ID, TargetVersion: m2.Version})
	if len(merchants.Target.Aliases) != 1 || merchants.Target.Aliases[0] != m1.Name {
		t.Fatal("merchant aliases")
	}
	found := invoke[Page[Spend]](t, s, p, "spends_search", SearchInput{Filter: Filter{Search: "STORE 123"}})
	if found.Total != 1 {
		t.Fatal("merchant alias search")
	}
	reject(t, s, p, "merchants_restore", LifecycleInput{Meta: meta(), Versioned: Versioned{m1.ID, merchants.Source.Version}}, "duplicate")
	invoke[Dimension](t, s, p, "merchants_update", UpdateDimensionInput{Meta: meta(), Versioned: Versioned{m2.ID, merchants.Target.Version}, DimensionInput: DimensionInput{Name: "New Store"}})
	restored := invoke[Dimension](t, s, p, "merchants_restore", LifecycleInput{Meta: meta(), Versioned: Versioned{m1.ID, merchants.Source.Version}})
	if restored.DeletedAt != nil {
		t.Fatal("restore merchant")
	}
	reject(t, s, p, "evidence_delete", DeleteEvidenceInput{Meta: meta(), Versioned: Versioned{e.ID, e.Version}}, "conflict")
	evidence := invoke[Evidence](t, s, p, "evidence_delete", DeleteEvidenceInput{Meta: meta(), Versioned: Versioned{e.ID, e.Version}, Detach: true})
	spend = invoke[Spend](t, s, p, "spends_get", GetInput{ID: spend.ID})
	if len(spend.EvidenceIDs) != 0 || evidence.DeletedAt == nil {
		t.Fatal("detach evidence")
	}
	evidence = invoke[Evidence](t, s, p, "evidence_restore", LifecycleInput{Meta: meta(), Versioned: Versioned{e.ID, evidence.Version}})
	spend = invoke[Spend](t, s, p, "evidence_attach", EvidenceLinkInput{Meta: meta(), Versioned: v(spend), EvidenceID: e.ID})
	if len(spend.EvidenceIDs) != 1 {
		t.Fatal("attach")
	}
	spend = invoke[Spend](t, s, p, "evidence_detach", EvidenceLinkInput{Meta: meta(), Versioned: v(spend), EvidenceID: e.ID})
	if len(spend.EvidenceIDs) != 0 {
		t.Fatal("detach")
	}
	// Reassignment also updates deleted records so they remain restorable.
	spend = invoke[Spend](t, s, p, "spends_delete", LifecycleInput{Meta: meta(), Versioned: v(spend)})
	c := dim(t, s, p, "categories", "General")
	invoke[Dimension](t, s, p, "categories_delete", DeleteDimensionInput{Meta: meta(), Versioned: Versioned{b.ID, merged.Target.Version}, ReplacementID: c.ID, ReplacementVersion: c.Version})
	spend = invoke[Spend](t, s, p, "spends_get", GetInput{ID: spend.ID})
	spend = invoke[Spend](t, s, p, "spends_restore", LifecycleInput{Meta: meta(), Versioned: v(spend)})
	if spend.Allocations[0].CategoryID != c.ID {
		t.Fatal("deleted spend reassignment")
	}
	// Names are scoped by user and kind and case-insensitively unique.
	reject(t, s, p, "categories_create", CreateDimensionInput{Meta: meta(), DimensionInput: DimensionInput{Name: "gEnErAl"}}, "duplicate")
	dim(t, s, p, "tags", "General")
	list := invoke[Page[Dimension]](t, s, p, "categories_list", PageInput{State: "deleted"})
	if list.Total != 2 {
		t.Fatalf("deleted categories: %+v", list)
	}
}
func TestRefundsDeletionAndLargeTotals(t *testing.T) {
	s, p := setup(t)
	expense := create(t, s, p, spendInput(maxAmount))
	create(t, s, p, spendInput(maxAmount))
	totals := invoke[Analysis](t, s, p, "spending_analyze", AnalysisInput{})
	if totals.Current[0].NetMinor != "18014398509481982" {
		t.Fatal("aggregate lost precision")
	}
	refundInput := spendInput(50)
	refundInput.Kind = "refund"
	refundInput.OriginalSpendID = expense.ID
	refund := create(t, s, p, refundInput)
	reject(t, s, p, "spends_delete", LifecycleInput{Meta: meta(), Versioned: v(expense)}, "conflict")
	changed := expense.SpendInput
	changed.Currency = "INR"
	reject(t, s, p, "spends_update", UpdateSpendInput{Meta: meta(), Versioned: v(expense), Spend: changed}, "conflict")
	deleted := invoke[SpendsResult](t, s, p, "spends_bulk_delete", BulkLifecycleInput{Meta: meta(), Records: []Versioned{v(expense), v(refund)}})
	active := invoke[Page[Spend]](t, s, p, "spends_search", SearchInput{})
	if active.Total != 1 {
		t.Fatal("deleted records included")
	}
	restored := invoke[SpendsResult](t, s, p, "spends_bulk_restore", BulkLifecycleInput{Meta: meta(), Records: []Versioned{v(deleted.Items[1]), v(deleted.Items[0])}})
	if len(restored.Items) != 2 || restored.Items[0].DeletedAt != nil {
		t.Fatal("paired restoration")
	}
	refundInput.Currency = "INR"
	reject(t, s, p, "spends_create", CreateSpendInput{Meta: meta(), Spend: refundInput}, "invalid_input")
}
func TestDatesAndCSV(t *testing.T) {
	for _, d := range []string{"2026-02-29", "2026-1-01", "0000-01-01", "2026-10-04T00:00:00Z"} {
		if date(d) == nil {
			t.Fatal(d)
		}
	}
	if date("2024-02-29") != nil {
		t.Fatal("leap day")
	}
	for _, s := range []string{"=1+1", " +cmd", "\tvalue", "@SUM(A1)", "-1+1"} {
		if safeCSV(s) != "'"+s {
			t.Fatal(s)
		}
	}
	if safeCSV("Normal") != "Normal" {
		t.Fatal("normal CSV")
	}
}

func TestRemainingRecordLifecycles(t *testing.T) {
	s, p := setup(t)
	for _, kind := range []string{"categories", "tags", "merchants"} {
		record := dim(t, s, p, kind, "First")
		got := invoke[Dimension](t, s, p, kind+"_get", GetInput{ID: record.ID})
		if got.ID != record.ID {
			t.Fatal("get")
		}
		record = invoke[Dimension](t, s, p, kind+"_update", UpdateDimensionInput{Meta: meta(), Versioned: Versioned{record.ID, record.Version}, DimensionInput: DimensionInput{Name: "Renamed"}})
		list := invoke[Page[Dimension]](t, s, p, kind+"_list", PageInput{Search: "name"})
		if list.Total != 1 {
			t.Fatal("list")
		}
		record = invoke[Dimension](t, s, p, kind+"_delete", DeleteDimensionInput{Meta: meta(), Versioned: Versioned{record.ID, record.Version}})
		record = invoke[Dimension](t, s, p, kind+"_restore", LifecycleInput{Meta: meta(), Versioned: Versioned{record.ID, record.Version}})
		if record.DeletedAt != nil {
			t.Fatal("restore")
		}
	}
	evidence := invoke[Evidence](t, s, p, "evidence_create", CreateEvidenceInput{Meta: meta(), EvidenceInput: EvidenceInput{Title: "Source", SourceRef: "doc:old"}})
	evidence = invoke[Evidence](t, s, p, "evidence_update", UpdateEvidenceInput{Meta: meta(), Versioned: Versioned{evidence.ID, evidence.Version}, EvidenceInput: EvidenceInput{Title: "Corrected source", SourceRef: "doc:new"}})
	got := invoke[Evidence](t, s, p, "evidence_get", GetInput{ID: evidence.ID})
	if got.SourceRef != "doc:new" {
		t.Fatal("evidence update")
	}
	list := invoke[Page[Evidence]](t, s, p, "evidence_list", PageInput{Search: "new"})
	if list.Total != 1 {
		t.Fatal("evidence search")
	}
	batch := invoke[SpendsResult](t, s, p, "spends_bulk_create", BulkCreateInput{Meta: meta(), Spends: []SpendInput{spendInput(100), spendInput(200)}})
	if len(batch.Items) != 2 {
		t.Fatal("bulk create")
	}
	// Category reassignment remains possible for deleted refunds whose original is deleted.
	category := dim(t, s, p, "categories", "Old category")
	replacement := dim(t, s, p, "categories", "New category")
	input := spendInput(50)
	input.Allocations = []Allocation{{category.ID, 50}}
	original := create(t, s, p, input)
	input.Kind = "refund"
	input.OriginalSpendID = original.ID
	refund := create(t, s, p, input)
	invoke[SpendsResult](t, s, p, "spends_bulk_delete", BulkLifecycleInput{Meta: meta(), Records: []Versioned{v(original), v(refund)}})
	merged := invoke[MergeResult](t, s, p, "categories_merge", MergeDimensionInput{Meta: meta(), Versioned: Versioned{category.ID, category.Version}, TargetID: replacement.ID, TargetVersion: replacement.Version})
	if merged.ChangedSpends != 2 {
		t.Fatal("deleted references were not reassigned")
	}
}
