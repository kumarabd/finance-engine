package engine

import (
	"context"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
)

func filterSQL(owner string, f Filter) (string, []any, error) {
	args := []any{owner}
	clauses := []string{"s.owner_id=$1"}
	add := func(expression string, value any) {
		args = append(args, value)
		clauses = append(clauses, fmt.Sprintf(expression, len(args)))
	}
	st, err := state(f.State)
	if err != nil {
		return "", nil, err
	}
	if st != "TRUE" {
		clauses = append(clauses, "s."+st)
	}
	if f.From != "" {
		if err = date(f.From); err != nil {
			return "", nil, err
		}
		add("s.occurred_on >= $%d::date", f.From)
	}
	if f.To != "" {
		if err = date(f.To); err != nil {
			return "", nil, err
		}
		add("s.occurred_on <= $%d::date", f.To)
	}
	if f.From != "" && f.To != "" && f.From > f.To {
		return "", nil, invalid("from must not be after to")
	}
	if f.Kind != "" {
		switch f.Kind {
		case "expense", "refund", "transfer":
		default:
			return "", nil, invalid("Invalid kind")
		}
		add("s.kind=$%d", f.Kind)
	}
	if f.Currency != "" {
		if !currencyPattern.MatchString(f.Currency) {
			return "", nil, invalid("Invalid currency")
		}
		add("s.currency=$%d", f.Currency)
	}
	for _, field := range []struct{ id, expr string }{
		{f.MerchantID, "s.merchant_id=$%d::uuid"},
		{f.CategoryID, "EXISTS(SELECT 1 FROM finance.spend_allocations ca WHERE ca.owner_id=s.owner_id AND ca.spend_id=s.id AND ca.category_id=$%d::uuid)"},
		{f.EvidenceID, "EXISTS(SELECT 1 FROM finance.spend_evidence se WHERE se.owner_id=s.owner_id AND se.spend_id=s.id AND se.evidence_id=$%d::uuid)"},
		{f.OriginalSpendID, "s.original_spend_id=$%d::uuid"},
	} {
		if field.id != "" {
			if err = uuid(field.id); err != nil {
				return "", nil, err
			}
			add(field.expr, field.id)
		}
	}
	if f.Uncategorized {
		if f.CategoryID != "" {
			return "", nil, invalid("category_id and uncategorized cannot be combined")
		}
		clauses = append(clauses, "EXISTS(SELECT 1 FROM finance.spend_allocations ca WHERE ca.owner_id=s.owner_id AND ca.spend_id=s.id AND ca.category_id IS NULL)")
	}
	tags, err := uniqueIDs(f.TagIDs)
	if err != nil {
		return "", nil, err
	}
	if len(tags) > 0 {
		args = append(args, tags)
		clauses = append(clauses, fmt.Sprintf("(SELECT count(*) FROM finance.spend_tags st WHERE st.owner_id=s.owner_id AND st.spend_id=s.id AND st.tag_id=ANY($%d::uuid[]))=%d", len(args), len(tags)))
	}
	if len(f.AccountRef) > 255 || len(f.Search) > 200 {
		return "", nil, invalid("Search or account reference is too long")
	}
	if f.AccountRef != "" {
		add("s.account_ref=$%d", f.AccountRef)
	}
	if f.MinAmount != nil {
		if *f.MinAmount < 0 || *f.MinAmount > maxAmount {
			return "", nil, invalid("Invalid minimum amount")
		}
		add("s.amount_minor >= $%d", *f.MinAmount)
	}
	if f.MaxAmount != nil {
		if *f.MaxAmount < 0 || *f.MaxAmount > maxAmount {
			return "", nil, invalid("Invalid maximum amount")
		}
		add("s.amount_minor <= $%d", *f.MaxAmount)
	}
	if f.MinAmount != nil && f.MaxAmount != nil && *f.MinAmount > *f.MaxAmount {
		return "", nil, invalid("Minimum amount exceeds maximum")
	}
	if f.Search != "" {
		args = append(args, literal(f.Search))
		i := len(args)
		clauses = append(clauses, fmt.Sprintf("(s.description ILIKE $%d OR s.account_ref ILIKE $%d OR EXISTS(SELECT 1 FROM finance.dimensions m WHERE m.owner_id=s.owner_id AND m.id=s.merchant_id AND (m.name ILIKE $%d OR m.aliases::text ILIKE $%d)))", i, i, i, i))
	}
	return strings.Join(clauses, " AND "), args, nil
}
func searchSpends(ctx context.Context, u *unit, input SearchInput) (Page[Spend], error) {
	result := Page[Spend]{Items: []Spend{}}
	limit, err := page(input.Limit, input.Offset)
	if err != nil {
		return result, err
	}
	where, args, err := filterSQL(u.owner, input.Filter)
	if err != nil {
		return result, err
	}
	order := "s.occurred_on DESC,s.id DESC"
	switch input.Sort {
	case "", "date_desc":
	case "date_asc":
		order = "s.occurred_on ASC,s.id ASC"
	case "amount_desc", "amount_asc":
		if input.Currency == "" {
			return result, invalid("Amount sorting requires a currency filter")
		}
		direction := "DESC"
		if input.Sort == "amount_asc" {
			direction = "ASC"
		}
		order = "s.amount_minor " + direction + ",s.id " + direction
	default:
		return result, invalid("Unknown sort order")
	}
	if err = u.tx.QueryRow(ctx, "SELECT count(*) FROM finance.spends s WHERE "+where, args...).Scan(&result.Total); err != nil {
		return result, err
	}
	args = append(args, limit, input.Offset)
	rows, err := u.tx.Query(ctx, "SELECT "+spendColumns+" FROM finance.spends s WHERE "+where+" ORDER BY "+order+fmt.Sprintf(" LIMIT $%d OFFSET $%d", len(args)-1, len(args)), args...)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		s, err := scanSpend(rows)
		if err != nil {
			return result, err
		}
		result.Items = append(result.Items, s)
	}
	result.NextOffset = nextOffset(input.Offset, len(result.Items), result.Total)
	return result, rows.Err()
}

func analyze(ctx context.Context, u *unit, input AnalysisInput) (Analysis, error) {
	group := input.GroupBy
	if group == "" {
		group = "total"
	}
	result := Analysis{Current: []Bucket{}, Comparison: []Bucket{}, GroupBy: group, GroupsOverlap: group == "tag"}
	current, err := u.buckets(ctx, input.Filter, group)
	if err != nil {
		return result, err
	}
	result.Current = current
	if input.CompareFrom != "" || input.CompareTo != "" {
		if input.From == "" || input.To == "" || input.CompareFrom == "" || input.CompareTo == "" {
			return result, invalid("Comparison requires from, to, compare_from and compare_to")
		}
		filter := input.Filter
		filter.From = input.CompareFrom
		filter.To = input.CompareTo
		result.Comparison, err = u.buckets(ctx, filter, group)
	}
	return result, err
}
func (u *unit) buckets(ctx context.Context, f Filter, group string) ([]Bucket, error) {
	where, args, err := filterSQL(u.owner, f)
	if err != nil {
		return nil, err
	}
	where += " AND s.kind <> 'transfer'"
	key, label := "'total'", "'Total'"
	joins := " JOIN finance.spend_allocations a ON a.owner_id=s.owner_id AND a.spend_id=s.id"
	switch group {
	case "total":
	case "category":
		joins += " LEFT JOIN finance.dimensions d ON d.owner_id=a.owner_id AND d.id=a.category_id"
		key = "COALESCE(a.category_id::text,'uncategorized')"
		label = "COALESCE(d.name,'Uncategorized')"
	case "merchant":
		joins += " LEFT JOIN finance.dimensions d ON d.owner_id=s.owner_id AND d.id=s.merchant_id"
		key = "COALESCE(s.merchant_id::text,'unknown')"
		label = "COALESCE(d.name,'Unknown merchant')"
	case "tag":
		joins += " LEFT JOIN finance.spend_tags t ON t.owner_id=s.owner_id AND t.spend_id=s.id LEFT JOIN finance.dimensions d ON d.owner_id=t.owner_id AND d.id=t.tag_id"
		key = "COALESCE(t.tag_id::text,'untagged')"
		label = "COALESCE(d.name,'Untagged')"
	case "day":
		key = "s.occurred_on::text"
		label = key
	case "week", "month":
		key = "date_trunc('" + group + "',s.occurred_on)::date::text"
		label = key
	default:
		return nil, invalid("group_by must be total, category, merchant, tag, day, week, or month")
	}
	// A category drilldown includes only that part of a split purchase.
	if f.CategoryID != "" {
		args = append(args, f.CategoryID)
		where += fmt.Sprintf(" AND a.category_id=$%d::uuid", len(args))
	}
	if f.Uncategorized {
		where += " AND a.category_id IS NULL"
	}
	query := "SELECT s.currency," + key + "," + label + `,
        COALESCE(sum(CASE WHEN s.kind='expense' THEN a.amount_minor ELSE 0 END),0)::text,
        COALESCE(sum(CASE WHEN s.kind='refund' THEN a.amount_minor ELSE 0 END),0)::text,
        COALESCE(sum(CASE WHEN s.kind='refund' THEN -a.amount_minor ELSE a.amount_minor END),0)::text,
        count(DISTINCT s.id) FROM finance.spends s` + joins + " WHERE " + where + " GROUP BY 1,2,3 ORDER BY 1,2 LIMIT 2001"
	rows, err := u.tx.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []Bucket{}
	for rows.Next() {
		var b Bucket
		if err = rows.Scan(&b.Currency, &b.Key, &b.Label, &b.ExpenseMinor, &b.RefundMinor, &b.NetMinor, &b.Count); err != nil {
			return nil, err
		}
		result = append(result, b)
	}
	if len(result) > 2000 {
		return nil, invalid("More than 2000 groups; narrow the date range or filters")
	}
	return result, rows.Err()
}
func exportSpends(ctx context.Context, u *unit, input ExportInput) (ExportResult, error) {
	result := ExportResult{}
	page, err := searchSpends(ctx, u, input.SearchInput)
	if err != nil {
		return result, err
	}
	names := map[string]string{}
	rows, err := u.tx.Query(ctx, "SELECT id::text,name FROM finance.dimensions WHERE owner_id=$1", u.owner)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var id, name string
		if err = rows.Scan(&id, &name); err != nil {
			rows.Close()
			return result, err
		}
		names[id] = name
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	var out strings.Builder
	writer := csv.NewWriter(&out)
	_ = writer.Write([]string{"id", "version", "occurred_on", "kind", "amount_minor", "currency", "merchant", "description", "account_ref", "category_allocations", "tags", "evidence_ids", "original_spend_id", "source", "source_record_id", "deleted_at"})
	for _, s := range page.Items {
		allocations := make([]map[string]any, 0, len(s.Allocations))
		for _, a := range s.Allocations {
			name := names[a.CategoryID]
			if a.CategoryID == "" {
				name = "Uncategorized"
			}
			allocations = append(allocations, map[string]any{"category": name, "amount_minor": a.AmountMinor})
		}
		tags := []string{}
		for _, id := range s.TagIDs {
			tags = append(tags, names[id])
		}
		a, _ := json.Marshal(allocations)
		t, _ := json.Marshal(tags)
		e, _ := json.Marshal(s.EvidenceIDs)
		deleted := ""
		if s.DeletedAt != nil {
			deleted = s.DeletedAt.UTC().Format("2006-01-02T15:04:05Z07:00")
		}
		cells := []string{s.ID, strconv.FormatInt(s.Version, 10), s.OccurredOn, s.Kind, strconv.FormatInt(s.AmountMinor, 10), s.Currency, names[s.MerchantID], s.Description, s.AccountRef, string(a), string(t), string(e), s.OriginalSpendID, s.Source, s.SourceRecordID, deleted}
		for i, cell := range cells {
			cells[i] = safeCSV(cell)
		}
		if err = writer.Write(cells); err != nil {
			return result, err
		}
	}
	writer.Flush()
	if err = writer.Error(); err != nil {
		return result, err
	}
	result.CSV = out.String()
	result.Total = page.Total
	result.NextOffset = page.NextOffset
	return result, nil
}
func safeCSV(value string) string {
	trimmed := strings.TrimSpace(value)
	if strings.HasPrefix(value, "\t") || strings.HasPrefix(value, "\r") || strings.HasPrefix(value, "\n") || (len(trimmed) > 0 && strings.ContainsRune("=+-@", rune(trimmed[0]))) {
		return "'" + value
	}
	return value
}
func history(ctx context.Context, u *unit, input HistoryInput) (Page[Change], error) {
	result := Page[Change]{Items: []Change{}}
	limit, err := page(input.Limit, input.Offset)
	if err != nil {
		return result, err
	}
	switch input.EntityType {
	case "spend":
		_, err = u.getSpend(ctx, input.ID)
	case "category", "tag", "merchant":
		_, err = u.getDimension(ctx, input.ID, input.EntityType)
	case "evidence":
		_, err = u.getEvidence(ctx, input.ID)
	default:
		return result, invalid("Unknown entity_type")
	}
	if err != nil {
		return result, err
	}
	if err = u.tx.QueryRow(ctx, "SELECT count(*) FROM finance.changes WHERE owner_id=$1 AND entity_type=$2 AND entity_id=$3", u.owner, input.EntityType, input.ID).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := u.tx.Query(ctx, `SELECT id::text,occurred_at,actor_id,operation,before_data,after_data FROM finance.changes
        WHERE owner_id=$1 AND entity_type=$2 AND entity_id=$3 ORDER BY occurred_at DESC,id DESC LIMIT $4 OFFSET $5`, u.owner, input.EntityType, input.ID, limit, input.Offset)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var c Change
		if err = rows.Scan(&c.ID, &c.OccurredAt, &c.Actor, &c.Operation, &c.Before, &c.After); err != nil {
			return result, err
		}
		c.OccurredAt = c.OccurredAt.UTC()
		result.Items = append(result.Items, c)
	}
	result.NextOffset = nextOffset(input.Offset, len(result.Items), result.Total)
	return result, rows.Err()
}
