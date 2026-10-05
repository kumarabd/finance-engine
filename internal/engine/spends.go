package engine

import (
	"context"
	"encoding/json"
	"sort"
	"strings"
)

const spendColumns = `s.id::text,s.version,s.occurred_on::text,s.kind,s.amount_minor,s.currency,
    COALESCE(s.merchant_id::text,''),s.description,s.account_ref,COALESCE(s.original_spend_id::text,''),
    s.source,s.source_record_id,s.deleted_at,s.created_at,s.updated_at,
    COALESCE((SELECT jsonb_agg(jsonb_build_object('category_id',COALESCE(a.category_id::text,''),'amount_minor',a.amount_minor) ORDER BY a.position)
        FROM finance.spend_allocations a WHERE a.owner_id=s.owner_id AND a.spend_id=s.id),'[]'),
    COALESCE((SELECT jsonb_agg(t.tag_id::text ORDER BY t.tag_id) FROM finance.spend_tags t WHERE t.owner_id=s.owner_id AND t.spend_id=s.id),'[]'),
    COALESCE((SELECT jsonb_agg(e.evidence_id::text ORDER BY e.evidence_id) FROM finance.spend_evidence e WHERE e.owner_id=s.owner_id AND e.spend_id=s.id),'[]')`

type scanner interface{ Scan(...any) error }

func scanSpend(row scanner) (Spend, error) {
	var s Spend
	var allocations, tags, evidence []byte
	err := row.Scan(&s.ID, &s.Version, &s.OccurredOn, &s.Kind, &s.AmountMinor, &s.Currency, &s.MerchantID,
		&s.Description, &s.AccountRef, &s.OriginalSpendID, &s.Source, &s.SourceRecordID, &s.DeletedAt, &s.CreatedAt, &s.UpdatedAt,
		&allocations, &tags, &evidence)
	if err != nil {
		return s, err
	}
	if err = json.Unmarshal(allocations, &s.Allocations); err != nil {
		return s, err
	}
	if err = json.Unmarshal(tags, &s.TagIDs); err != nil {
		return s, err
	}
	if err = json.Unmarshal(evidence, &s.EvidenceIDs); err != nil {
		return s, err
	}
	s.CreatedAt = s.CreatedAt.UTC()
	s.UpdatedAt = s.UpdatedAt.UTC()
	return s, nil
}
func (u *unit) getSpend(ctx context.Context, id string) (Spend, error) {
	if err := uuid(id); err != nil {
		return Spend{}, err
	}
	return scanSpend(u.tx.QueryRow(ctx, "SELECT "+spendColumns+" FROM finance.spends s WHERE s.owner_id=$1 AND s.id=$2", u.owner, id))
}
func (u *unit) validateSpend(ctx context.Context, s *SpendInput, self string, allowDeletedOriginal bool) error {
	if err := date(s.OccurredOn); err != nil {
		return err
	}
	switch s.Kind {
	case "expense", "refund", "transfer":
	default:
		return invalid("kind must be expense, refund, or transfer")
	}
	if s.AmountMinor < 1 || s.AmountMinor > maxAmount {
		return invalid("amount_minor must be a positive safe integer")
	}
	if !currencyPattern.MatchString(s.Currency) {
		return invalid("currency must be three uppercase letters")
	}
	if len(s.Description) > 4000 || len(s.AccountRef) > 255 || len(s.Source) > 128 || len(s.SourceRecordID) > 255 {
		return invalid("Description or supporting reference is too long")
	}
	if (s.Source == "") != (s.SourceRecordID == "") {
		return invalid("source and source_record_id must be supplied together")
	}
	s.MerchantID = strings.ToLower(s.MerchantID)
	s.OriginalSpendID = strings.ToLower(s.OriginalSpendID)
	if s.MerchantID != "" {
		if err := u.activeDimension(ctx, s.MerchantID, "merchant"); err != nil {
			return err
		}
	}
	if s.OriginalSpendID != "" {
		if s.Kind != "refund" || s.OriginalSpendID == self {
			return invalid("Only a refund may reference a different original expense")
		}
		original, err := u.getSpend(ctx, s.OriginalSpendID)
		if err != nil {
			return err
		}
		if (original.DeletedAt != nil && !allowDeletedOriginal) || original.Kind != "expense" || original.Currency != s.Currency {
			return invalid("Refund must reference an active expense in the same currency")
		}
	}
	if self != "" {
		var invalidRefund bool
		err := u.tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM finance.spends WHERE owner_id=$1 AND original_spend_id=$2
            AND deleted_at IS NULL AND ($3 <> 'expense' OR currency <> $4))`, u.owner, self, s.Kind, s.Currency).Scan(&invalidRefund)
		if err != nil {
			return err
		}
		if invalidRefund {
			return conflict("Existing refunds require this record to remain an expense in their currency")
		}
	}
	if len(s.Allocations) == 0 {
		s.Allocations = []Allocation{{AmountMinor: s.AmountMinor}}
	}
	if len(s.Allocations) > 100 {
		return invalid("At most 100 category splits are allowed")
	}
	total := int64(0)
	seen := map[string]bool{}
	for i := range s.Allocations {
		a := &s.Allocations[i]
		a.CategoryID = strings.ToLower(a.CategoryID)
		if a.AmountMinor < 1 || a.AmountMinor > s.AmountMinor-total {
			return invalid("Positive category allocations must sum exactly to amount_minor")
		}
		total += a.AmountMinor
		if seen[a.CategoryID] {
			return invalid("Combine repeated category allocations")
		}
		seen[a.CategoryID] = true
		if a.CategoryID != "" {
			if err := u.activeDimension(ctx, a.CategoryID, "category"); err != nil {
				return err
			}
		}
	}
	if total != s.AmountMinor {
		return invalid("Category allocations must sum exactly to amount_minor")
	}
	var err error
	s.TagIDs, err = uniqueIDs(s.TagIDs)
	if err != nil {
		return err
	}
	s.EvidenceIDs, err = uniqueIDs(s.EvidenceIDs)
	if err != nil {
		return err
	}
	for _, id := range s.TagIDs {
		if err := u.activeDimension(ctx, id, "tag"); err != nil {
			return err
		}
	}
	for _, id := range s.EvidenceIDs {
		evidence, err := u.getEvidence(ctx, id)
		if err != nil {
			return err
		}
		if evidence.DeletedAt != nil {
			return conflict("Cannot link deleted evidence")
		}
	}
	return nil
}
func (u *unit) saveLinks(ctx context.Context, id string, s SpendInput) error {
	for _, table := range []string{"spend_allocations", "spend_tags", "spend_evidence"} {
		if _, err := u.tx.Exec(ctx, "DELETE FROM finance."+table+" WHERE owner_id=$1 AND spend_id=$2", u.owner, id); err != nil {
			return err
		}
	}
	for i, a := range s.Allocations {
		_, err := u.tx.Exec(ctx, `INSERT INTO finance.spend_allocations(owner_id,spend_id,position,category_id,amount_minor)
            VALUES($1,$2,$3,NULLIF($4,'')::uuid,$5)`, u.owner, id, i, a.CategoryID, a.AmountMinor)
		if err != nil {
			return err
		}
	}
	for _, tag := range s.TagIDs {
		if _, err := u.tx.Exec(ctx, "INSERT INTO finance.spend_tags(owner_id,spend_id,tag_id) VALUES($1,$2,$3)", u.owner, id, tag); err != nil {
			return err
		}
	}
	for _, evidence := range s.EvidenceIDs {
		if _, err := u.tx.Exec(ctx, "INSERT INTO finance.spend_evidence(owner_id,spend_id,evidence_id) VALUES($1,$2,$3)", u.owner, id, evidence); err != nil {
			return err
		}
	}
	return nil
}
func (u *unit) createSpend(ctx context.Context, input SpendInput) (Spend, error) {
	if err := u.validateSpend(ctx, &input, "", false); err != nil {
		return Spend{}, err
	}
	var id string
	err := u.tx.QueryRow(ctx, `INSERT INTO finance.spends(owner_id,occurred_on,kind,amount_minor,currency,merchant_id,
        description,account_ref,original_spend_id,source,source_record_id)
        VALUES($1,$2::date,$3,$4,$5,NULLIF($6,'')::uuid,$7,$8,NULLIF($9,'')::uuid,$10,$11) RETURNING id::text`,
		u.owner, input.OccurredOn, input.Kind, input.AmountMinor, input.Currency, input.MerchantID, input.Description,
		input.AccountRef, input.OriginalSpendID, input.Source, input.SourceRecordID).Scan(&id)
	if err != nil {
		return Spend{}, err
	}
	if err = u.saveLinks(ctx, id, input); err != nil {
		return Spend{}, err
	}
	result, err := u.getSpend(ctx, id)
	if err != nil {
		return result, err
	}
	return result, u.audit(ctx, "spend", id, nil, result)
}
func (u *unit) updateSpend(ctx context.Context, id string, expected int64, input SpendInput) (Spend, error) {
	before, err := u.getSpend(ctx, id)
	if err != nil {
		return Spend{}, err
	}
	if err = version(expected, before.Version); err != nil {
		return Spend{}, err
	}
	if before.DeletedAt != nil {
		return Spend{}, conflict("Restore the spend before editing")
	}
	return u.replaceSpend(ctx, before, input)
}

// Used also by atomic taxonomy reassignments, including references on deleted records.
func (u *unit) replaceSpend(ctx context.Context, before Spend, input SpendInput) (Spend, error) {
	if err := u.validateSpend(ctx, &input, before.ID, before.DeletedAt != nil); err != nil {
		return Spend{}, err
	}
	_, err := u.tx.Exec(ctx, `UPDATE finance.spends SET occurred_on=$3::date,kind=$4,amount_minor=$5,currency=$6,
        merchant_id=NULLIF($7,'')::uuid,description=$8,account_ref=$9,original_spend_id=NULLIF($10,'')::uuid,
        source=$11,source_record_id=$12,version=version+1,updated_at=clock_timestamp() WHERE owner_id=$1 AND id=$2`,
		u.owner, before.ID, input.OccurredOn, input.Kind, input.AmountMinor, input.Currency, input.MerchantID, input.Description,
		input.AccountRef, input.OriginalSpendID, input.Source, input.SourceRecordID)
	if err != nil {
		return Spend{}, err
	}
	if err = u.saveLinks(ctx, before.ID, input); err != nil {
		return Spend{}, err
	}
	result, err := u.getSpend(ctx, before.ID)
	if err != nil {
		return result, err
	}
	return result, u.audit(ctx, "spend", before.ID, before, result)
}
func (u *unit) lifecycleSpend(ctx context.Context, id string, expected int64, restore bool) (Spend, error) {
	before, err := u.getSpend(ctx, id)
	if err != nil {
		return Spend{}, err
	}
	if err = version(expected, before.Version); err != nil {
		return Spend{}, err
	}
	if restore {
		if before.DeletedAt == nil {
			return Spend{}, conflict("Spend is already active")
		}
		input := before.SpendInput
		if err = u.validateSpend(ctx, &input, before.ID, false); err != nil {
			return Spend{}, err
		}
	} else {
		if before.DeletedAt != nil {
			return Spend{}, conflict("Spend is already deleted")
		}
		var linked bool
		err = u.tx.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM finance.spends WHERE owner_id=$1 AND original_spend_id=$2 AND deleted_at IS NULL)", u.owner, before.ID).Scan(&linked)
		if err != nil {
			return Spend{}, err
		}
		if linked {
			return Spend{}, conflict("Delete or unlink active refunds before removing their original expense")
		}
	}
	_, err = u.tx.Exec(ctx, `UPDATE finance.spends SET deleted_at=CASE WHEN $3 THEN NULL ELSE clock_timestamp() END,
        version=version+1,updated_at=clock_timestamp() WHERE owner_id=$1 AND id=$2`, u.owner, before.ID, restore)
	if err != nil {
		return Spend{}, err
	}
	after, err := u.getSpend(ctx, before.ID)
	if err != nil {
		return Spend{}, err
	}
	return after, u.audit(ctx, "spend", before.ID, before, after)
}
func applyPatch(s SpendInput, p SpendPatch) (SpendInput, error) {
	raw, _ := json.Marshal(p)
	if string(raw) == "{}" {
		return s, invalid("patch must contain at least one change")
	}
	if p.CategoryID != nil && p.Allocations != nil {
		return s, invalid("Use category_id or allocations, not both")
	}
	if p.TagIDs != nil && (len(p.AddTagIDs) > 0 || len(p.RemoveTagIDs) > 0) {
		return s, invalid("Use tag_ids or add/remove_tag_ids, not both")
	}
	if p.OccurredOn != nil {
		s.OccurredOn = *p.OccurredOn
	}
	if p.Kind != nil {
		s.Kind = *p.Kind
	}
	if p.Currency != nil {
		s.Currency = *p.Currency
	}
	if p.AmountMinor != nil {
		s.AmountMinor = *p.AmountMinor
		if len(s.Allocations) == 1 {
			s.Allocations[0].AmountMinor = s.AmountMinor
		}
	}
	if p.MerchantID != nil {
		s.MerchantID = *p.MerchantID
	}
	if p.Description != nil {
		s.Description = *p.Description
	}
	if p.AccountRef != nil {
		s.AccountRef = *p.AccountRef
	}
	if p.OriginalSpendID != nil {
		s.OriginalSpendID = *p.OriginalSpendID
	}
	if p.CategoryID != nil {
		s.Allocations = []Allocation{{CategoryID: *p.CategoryID, AmountMinor: s.AmountMinor}}
	}
	if p.Allocations != nil {
		s.Allocations = *p.Allocations
	}
	if p.TagIDs != nil {
		s.TagIDs = *p.TagIDs
	}
	if p.EvidenceIDs != nil {
		s.EvidenceIDs = *p.EvidenceIDs
	}
	add, err := uniqueIDs(p.AddTagIDs)
	if err != nil {
		return s, err
	}
	remove, err := uniqueIDs(p.RemoveTagIDs)
	if err != nil {
		return s, err
	}
	tags := map[string]bool{}
	for _, id := range s.TagIDs {
		tags[id] = true
	}
	adding := map[string]bool{}
	for _, id := range add {
		tags[id] = true
		adding[id] = true
	}
	for _, id := range remove {
		if adding[id] {
			return s, invalid("A tag cannot be added and removed together")
		}
		delete(tags, id)
	}
	s.TagIDs = make([]string, 0, len(tags))
	for id := range tags {
		s.TagIDs = append(s.TagIDs, id)
	}
	sort.Strings(s.TagIDs)
	return s, nil
}
func validateBatch(records []Versioned) error {
	if len(records) < 1 || len(records) > 100 {
		return invalid("A batch must have 1-100 records")
	}
	ids := make([]string, len(records))
	for i, r := range records {
		ids[i] = r.ID
		if r.ExpectedVersion < 1 {
			return invalid("Every batch record needs expected_version")
		}
	}
	_, err := uniqueIDs(ids)
	return err
}
func bulkUpdate(ctx context.Context, u *unit, input BulkUpdateInput) (SpendsResult, error) {
	result := SpendsResult{Items: []Spend{}}
	if err := validateBatch(input.Records); err != nil {
		return result, err
	}
	for _, record := range input.Records {
		s, err := u.getSpend(ctx, record.ID)
		if err != nil {
			return result, err
		}
		changed, err := applyPatch(s.SpendInput, input.Patch)
		if err != nil {
			return result, err
		}
		updated, err := u.updateSpend(ctx, record.ID, record.ExpectedVersion, changed)
		if err != nil {
			return result, err
		}
		result.Items = append(result.Items, updated)
	}
	return result, nil
}
func bulkLifecycle(ctx context.Context, u *unit, input BulkLifecycleInput, restore bool) (SpendsResult, error) {
	result := SpendsResult{Items: []Spend{}}
	if err := validateBatch(input.Records); err != nil {
		return result, err
	}
	records := append([]Versioned{}, input.Records...)
	kinds := map[string]string{}
	for _, r := range records {
		s, err := u.getSpend(ctx, r.ID)
		if err != nil {
			return result, err
		}
		kinds[r.ID] = s.Kind
	}
	// Remove refunds before expenses; restore expenses before their refunds.
	sort.SliceStable(records, func(i, j int) bool {
		a, b := kinds[records[i].ID] == "refund", kinds[records[j].ID] == "refund"
		if restore {
			return !a && b
		}
		return a && !b
	})
	results := map[string]Spend{}
	for _, r := range records {
		s, err := u.lifecycleSpend(ctx, r.ID, r.ExpectedVersion, restore)
		if err != nil {
			return result, err
		}
		results[r.ID] = s
	}
	for _, r := range input.Records {
		result.Items = append(result.Items, results[r.ID])
	}
	return result, nil
}
